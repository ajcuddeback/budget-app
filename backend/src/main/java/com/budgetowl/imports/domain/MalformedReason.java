package com.budgetowl.imports.domain;

/**
 * Why a file was refused as {@code import-file-malformed}: the fixed list from
 * docs/features/file-import.md, and nothing else.
 *
 * <p><b>This list is the only thing a refusal says about the file.</b> A StAX or CSV exception's
 * message quotes the text it choked on and its position — which is the content of somebody's bank
 * statement — so every parser failure is caught at the import boundary and replaced by one of
 * these. A new reason is a new entry here and in the client's translations, never a message.
 */
public enum MalformedReason {
    DTD("dtd"),
    BINARY("binary"),
    HEADER("header"),
    LINE_TOO_LONG("line-too-long"),
    TOO_MANY_COLUMNS("too-many-columns"),
    TOO_DEEP("too-deep"),
    TOO_MANY_ELEMENTS("too-many-elements"),
    ELEMENT_TOO_LONG("element-too-long"),
    ENCRYPTED("encrypted"),
    COMPRESSED("compressed"),
    NOT_A_STATEMENT("not-a-statement"),
    UNCLOSED_QUOTE("unclosed-quote");

    private final String code;

    MalformedReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
