package com.budgetowl.imports.service;

import com.budgetowl.account.domain.Account;
import com.budgetowl.account.persistence.AccountRepository;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.DomainException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import com.budgetowl.imports.domain.FileReading;
import com.budgetowl.imports.domain.ImportCharsets;
import com.budgetowl.imports.domain.ImportFormat;
import com.budgetowl.imports.domain.ImportReader;
import com.budgetowl.imports.domain.ImportRefusal;
import com.budgetowl.imports.domain.ReadOptions;
import com.budgetowl.imports.domain.ReadRow;
import com.budgetowl.imports.service.Classified.Classification;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * File import: preview, then commit (docs/features/file-import.md, ADR-0037, ADR-0038).
 *
 * <p><b>The order of every request is the point.</b> The role is checked first — before the account
 * is looked up and before a byte of the file is decoded — so a {@code VIEWER} gets {@code 403}
 * rather than learning whether an account exists, and a refused caller costs no parsing. Then the
 * rate limit and the concurrency gate. Only then the account, scoped to the caller's household, and
 * only then the file.
 *
 * <p><b>Not {@code @Transactional}.</b> A parse may take up to the deadline, and holding a database
 * connection for it would let two slow files starve the pool. The ledger is read in {@link
 * ImportClassifier}'s read-only transaction and written in {@link ImportWriter}'s, each as short as
 * it can be.
 *
 * <p><b>The file never outlives the request</b> (ADR-0038). It is decoded into a byte array that
 * goes out of scope when this method returns; nothing here writes it, its name or its hash
 * anywhere. <b>Nothing from it is logged</b>: one line per request carrying ids, the format, the
 * decoded size, the counts, the charset's allowlisted name, the duration and the outcome code.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    /** Rows needing attention returned at most (rule "Response: preview"). */
    public static final int ATTENTION_LIMIT = 500;

    /** New rows sampled in the ledger's own shape. */
    public static final int SAMPLE_SIZE = 20;

    /** Groups of identical rows explained. */
    static final int IDENTICAL_LIMIT = 20;

    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

    private final MembershipService memberships;
    private final AccountRepository accounts;
    private final ImportReader reader;
    private final ImportClassifier classifier;
    private final ImportWriter writer;
    private final ImportMappingService mappings;
    private final ImportHistoryService history;

    /**
     * Seven collaborators, above the style guide's "about five". Each is one step of the pipeline
     * the class Javadoc lists, and splitting the orchestration across two classes would hide the
     * order of those steps, which is the part a reviewer most needs to see in one place.
     */
    public ImportService(
            MembershipService memberships,
            AccountRepository accounts,
            ImportReader reader,
            ImportClassifier classifier,
            ImportWriter writer,
            ImportMappingService mappings,
            ImportHistoryService history) {
        this.memberships = memberships;
        this.accounts = accounts;
        this.reader = reader;
        this.classifier = classifier;
        this.writer = writer;
        this.mappings = mappings;
        this.history = history;
    }

    /**
     * A file and what to do with it. {@link #toString} prints the file's length, never the file.
     */
    public record ImportRequest(
            UUID accountId, ImportFormat format, String file, ImportSettings settings) {

        @Override
        public String toString() {
            return "ImportRequest[format="
                    + format
                    + ", fileLength="
                    + (file == null ? 0 : file.length())
                    + "]";
        }
    }

    /** The person's decisions at commit. */
    public record CommitChoices(
            String previewDigest, boolean includeSuspected, Map<Integer, Boolean> overrides) {

        @Override
        public String toString() {
            return "CommitChoices[overrides=" + overrides.size() + "]";
        }
    }

    // --------------------------------------------------------------------------------- preview

    /** Parses and classifies. <b>Writes nothing</b>: no row, no payee, no batch, no file. */
    public ImportPreview preview(UUID actorId, ImportRequest request) {
        Outcome outcome = new Outcome("preview", actorId, request);
        try {
            HouseholdMember membership = memberships.requireWriteAccess(actorId);
            UUID householdId = membership.household().id();
            outcome.householdId = householdId;
            // The role, rate limit and concurrency gate were applied before the body was read
            // (ImportAdmissionInterceptor); the role is checked again above for any other caller.
            Account account = requireOpenAccount(request.accountId(), householdId);
            byte[] bytes = decode(request.file());
            outcome.bytes = bytes.length;
            ReadOptions options =
                    ImportSettingsReader.read(
                            request.format(), request.settings(), account.lastFour().orElse(null));
            FileReading reading = reader.read(bytes, request.format(), account.currency(), options);
            outcome.charset = reading.charset();
            Boolean matchesSaved =
                    reading.csv() == null
                            ? null
                            : mappings.matchesSaved(
                                            householdId, account.id(), reading.csv().headerLabels())
                                    .orElse(null);
            if (!reading.needs().isEmpty()) {
                outcome.code = "needs-answer";
                return asking(account, reading, matchesSaved);
            }
            ImportClassifier.Result result = classifier.classify(householdId, account, reading);
            ImportPreview preview =
                    preview(
                            account,
                            reading,
                            result,
                            classifier.balanceOf(householdId, account),
                            matchesSaved);
            outcome.counts = preview.counts();
            return preview;
        } catch (DomainException refused) {
            outcome.code = refused.errorCode().code();
            throw refused;
        } finally {
            outcome.log();
        }
    }

    // ---------------------------------------------------------------------------------- commit

    /**
     * Re-parses the same file through the same path, and writes what the preview showed — or
     * nothing, and {@code 409 import-preview-stale}.
     *
     * <p>A commit whose digest names a batch this household already has is the retry of one that
     * succeeded: it is answered with that batch, and writes nothing (ADR-0037).
     */
    public ImportBatchView commit(UUID actorId, ImportRequest request, CommitChoices choices) {
        Outcome outcome = new Outcome("commit", actorId, request);
        try {
            HouseholdMember membership = memberships.requireWriteAccess(actorId);
            UUID householdId = membership.household().id();
            outcome.householdId = householdId;
            if (choices.previewDigest() == null
                    || !DIGEST.matcher(choices.previewDigest()).matches()) {
                throw ImportSettingsReader.invalid("previewDigest", "must be 64 lowercase hex");
            }
            // The role, rate limit and concurrency gate were applied before the body was read
            // (ImportAdmissionInterceptor); the role is checked again above for any other caller.
            Account account = requireAccount(request.accountId(), householdId);
            java.util.Optional<ImportBatchView> already =
                    history.alreadyWritten(householdId, account, choices.previewDigest());
            if (already.isPresent()) {
                outcome.code = "already-imported";
                outcome.batchId = already.get().id();
                return already.get();
            }
            requireOpen(account);
            byte[] bytes = decode(request.file());
            outcome.bytes = bytes.length;
            ReadOptions options =
                    ImportSettingsReader.read(
                            request.format(), request.settings(), account.lastFour().orElse(null));
            FileReading reading = reader.read(bytes, request.format(), account.currency(), options);
            outcome.charset = reading.charset();
            requireNothingToAsk(reading);

            boolean saveMapping =
                    request.format() == ImportFormat.CSV
                            && request.settings() != null
                            && request.settings().saves();
            ImportWriter.Written written =
                    writer.write(
                            actorId,
                            householdId,
                            account.id(),
                            reading,
                            new ImportWriter.Decisions(
                                    choices.previewDigest(),
                                    choices.includeSuspected(),
                                    choices.overrides(),
                                    saveMapping
                                            ? options.mapping().withDateFormat(reading.dateFormat())
                                            : null,
                                    reading.charset(),
                                    options.invertSign(),
                                    reading.csv() == null
                                            ? List.of()
                                            : reading.csv().headerLabels()));
            outcome.batchId = written.batch().id();
            outcome.counts = counts(written.classification().rows());
            return history.view(written.batch(), account, true);
        } catch (DomainException refused) {
            outcome.code = refused.errorCode().code();
            throw refused;
        } finally {
            outcome.log();
        }
    }

    // ------------------------------------------------------------------------------- internals

    /**
     * The strict decoder, never the MIME one: no line breaks, no stray characters. The size is
     * checked from the text's length first, so a body over the cap is refused before it is decoded
     * into memory.
     */
    private byte[] decode(String file) {
        if (file == null) {
            throw ImportSettingsReader.invalid("file", "is required");
        }
        long decodedAtMost = (long) file.length() / 4 * 3;
        if (decodedAtMost > reader.limits().maximumBytes() + 2L) {
            throw ImportRefusal.of(ErrorCode.IMPORT_FILE_TOO_LARGE);
        }
        try {
            return Base64.getDecoder().decode(file);
        } catch (IllegalArgumentException notBase64) {
            // The message quotes the offending character; it is not carried along.
            throw new InvalidRequestException(
                    ErrorCode.MALFORMED_REQUEST, "invalid base64", Map.of());
        }
    }

    private Account requireAccount(UUID accountId, UUID householdId) {
        if (accountId == null) {
            throw ImportSettingsReader.invalid("accountId", "is required");
        }
        return accounts.findByIdAndHouseholdId(accountId, householdId)
                .orElseThrow(() -> new NotFoundException("no such account"));
    }

    private Account requireOpenAccount(UUID accountId, UUID householdId) {
        Account account = requireAccount(accountId, householdId);
        requireOpen(account);
        return account;
    }

    private static void requireOpen(Account account) {
        if (account.isArchived()) {
            throw new ConflictException(
                    ErrorCode.ACCOUNT_ARCHIVED, "the account is archived; restore it to import");
        }
    }

    /** A commit cannot answer a question the preview asked; the person must answer it first. */
    private static void requireNothingToAsk(FileReading reading) {
        if (reading.needs().contains(FileReading.Need.CHARSET)) {
            throw ImportSettingsReader.invalid(
                    "options.charset", "choose the file's character set");
        }
        if (reading.needs().contains(FileReading.Need.MAPPING)) {
            throw ImportSettingsReader.invalid("options.columns", "map the file's columns");
        }
        if (reading.needs().contains(FileReading.Need.DATE_FORMAT)) {
            throw ImportSettingsReader.invalid(
                    "options.dateFormat", "choose how dates are written");
        }
    }

    private static ImportPreview asking(
            Account account, FileReading reading, Boolean matchesSaved) {
        return new ImportPreview(
                null,
                summary(account),
                reading.format(),
                reading.decodedBytes(),
                reading.charset(),
                reading.charsetSource(),
                reading.charsetCandidates(),
                ImportCharsets.allowedNames(),
                reading.dateFormat() == null ? null : reading.dateFormat().pattern(),
                reading.dateFormatSource(),
                reading.dateFormatCandidates().stream().map(format -> format.pattern()).toList(),
                reading.needs(),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                false,
                List.of(),
                List.of(),
                false,
                false,
                reading.csv(),
                matchesSaved);
    }

    private static ImportPreview preview(
            Account account,
            FileReading reading,
            ImportClassifier.Result result,
            BigDecimal balanceNow,
            Boolean matchesSaved) {
        BigDecimal moneyIn = BigDecimal.ZERO;
        BigDecimal moneyOut = BigDecimal.ZERO;
        BigDecimal suspectedTotal = BigDecimal.ZERO;
        List<ImportPreview.Attention> attention = new ArrayList<>();
        int attentionSeen = 0;
        List<ImportPreview.Sample> sample = new ArrayList<>();
        Map<String, ImportPreview.Identical> identical = new LinkedHashMap<>();
        Map<String, Integer> identicalCounts = new HashMap<>();
        boolean textCut = false;

        for (Classified classified : result.rows()) {
            ReadRow row = classified.row();
            textCut = textCut || row.payeeCut() || row.noteCut();
            switch (classified.classification()) {
                case NEW -> {
                    if (row.amount().signum() > 0) {
                        moneyIn = moneyIn.add(row.amount());
                    } else {
                        moneyOut = moneyOut.add(row.amount());
                    }
                    if (sample.size() < SAMPLE_SIZE) {
                        sample.add(
                                new ImportPreview.Sample(
                                        row.record(),
                                        row.date(),
                                        scaled(row.amount()),
                                        account.currency().getCurrencyCode(),
                                        row.payee(),
                                        row.note(),
                                        row.payeeCut(),
                                        row.noteCut()));
                    }
                    String key = row.date() + "|" + scaled(row.amount()) + "|" + row.payeeKey();
                    int seen = identicalCounts.merge(key, 1, Integer::sum);
                    if (seen > 1
                            && (identical.containsKey(key) || identical.size() < IDENTICAL_LIMIT)) {
                        identical.put(
                                key,
                                new ImportPreview.Identical(
                                        row.date(), scaled(row.amount()), row.payee(), seen));
                    }
                }
                case SUSPECTED -> {
                    suspectedTotal = suspectedTotal.add(row.amount());
                    attentionSeen++;
                    if (attention.size() < ATTENTION_LIMIT) {
                        attention.add(
                                attentionOf(
                                        classified,
                                        ImportPreview.AttentionKind.SUSPECTED_DUPLICATE));
                    }
                }
                case DUPLICATE -> {
                    attentionSeen++;
                    if (attention.size() < ATTENTION_LIMIT) {
                        attention.add(
                                attentionOf(classified, ImportPreview.AttentionKind.DUPLICATE));
                    }
                }
                case UNREADABLE -> {
                    attentionSeen++;
                    if (attention.size() < ATTENTION_LIMIT) {
                        attention.add(
                                new ImportPreview.Attention(
                                        row.record(),
                                        ImportPreview.AttentionKind.UNREADABLE,
                                        null,
                                        null,
                                        null,
                                        row.problem().code(),
                                        row.column(),
                                        row.text(),
                                        null));
                    }
                }
                case ZERO -> {
                    // Counted below; a zero row moves no money and needs no decision.
                }
            }
        }
        ImportPreview.Counts counts = counts(result.rows());
        BigDecimal after = balanceNow.add(moneyIn).add(moneyOut);
        return new ImportPreview(
                result.digest(),
                summary(account),
                reading.format(),
                reading.decodedBytes(),
                reading.charset(),
                reading.charsetSource(),
                List.of(),
                ImportCharsets.allowedNames(),
                reading.dateFormat() == null ? null : reading.dateFormat().pattern(),
                reading.dateFormatSource(),
                List.of(),
                reading.needs(),
                new ImportPreview.Statement(
                        reading.from(),
                        reading.to(),
                        reading.bankClosingBalance() == null
                                ? null
                                : scaled(reading.bankClosingBalance()),
                        reading.accountHint()),
                counts,
                new ImportPreview.Totals(
                        scaled(moneyIn), scaled(moneyOut), account.currency().getCurrencyCode()),
                scaled(balanceNow),
                scaled(after),
                scaled(after.add(suspectedTotal)),
                attention,
                attentionSeen > attention.size(),
                sample,
                List.copyOf(identical.values()),
                result.reusedIdentifier(),
                textCut,
                reading.csv(),
                matchesSaved);
    }

    private static ImportPreview.Attention attentionOf(
            Classified classified, ImportPreview.AttentionKind kind) {
        ReadRow row = classified.row();
        return new ImportPreview.Attention(
                row.record(),
                kind,
                row.date(),
                scaled(row.amount()),
                row.payee(),
                null,
                null,
                null,
                classified.resembles() == null
                        ? null
                        : new ImportPreview.Resembles(
                                classified.resembles(), classified.resemblesDate()));
    }

    static ImportPreview.Counts counts(List<Classified> rows) {
        int fresh = 0;
        int duplicate = 0;
        int suspected = 0;
        int unreadable = 0;
        int zero = 0;
        for (Classified row : rows) {
            Classification kind = row.classification();
            switch (kind) {
                case NEW -> fresh++;
                case DUPLICATE -> duplicate++;
                case SUSPECTED -> suspected++;
                case UNREADABLE -> unreadable++;
                case ZERO -> zero++;
            }
        }
        return new ImportPreview.Counts(rows.size(), fresh, duplicate, suspected, unreadable, zero);
    }

    private static ImportPreview.AccountSummary summary(Account account) {
        return new ImportPreview.AccountSummary(
                account.id(),
                account.name(),
                account.lastFour().orElse(null),
                account.currency().getCurrencyCode());
    }

    /** Scale 4 on the wire, like every other amount in this API. Exact: nothing here exceeds it. */
    static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.UNNECESSARY);
    }

    /**
     * The one log line per request: identifiers, the format, the decoded size, the counts, the
     * charset's allowlisted name, how long it took and how it ended. Nothing from the file.
     */
    private static final class Outcome {

        private final String action;
        private final UUID actorId;
        private final UUID accountId;
        private final ImportFormat format;
        private final long started = System.nanoTime();
        private UUID householdId;
        private UUID batchId;
        private int bytes;
        private String charset;
        private ImportPreview.Counts counts;
        private String code = "ok";

        Outcome(String action, UUID actorId, ImportRequest request) {
            this.action = action;
            this.actorId = actorId;
            this.accountId = request.accountId();
            this.format = request.format();
        }

        void log() {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.info(
                    "import {} householdId={} accountId={} byUserId={} format={} bytes={}"
                            + " read={} new={} duplicate={} suspected={} unreadable={} zero={}"
                            + " charset={} durationMs={} importBatchId={} outcome={}",
                    action,
                    householdId,
                    accountId,
                    actorId,
                    format,
                    bytes,
                    counts == null ? 0 : counts.read(),
                    counts == null ? 0 : counts.fresh(),
                    counts == null ? 0 : counts.duplicate(),
                    counts == null ? 0 : counts.suspected(),
                    counts == null ? 0 : counts.unreadable(),
                    counts == null ? 0 : counts.zero(),
                    charset,
                    durationMs,
                    batchId,
                    code);
        }
    }
}
