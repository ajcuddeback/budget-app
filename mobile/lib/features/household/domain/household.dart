import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/session/current_user.dart';

/// `GET /api/households/current`: the one household an instance holds (ADR-0026), and the
/// caller's own role in it, so the screen never has to ask twice.
final class Household {
  const Household({
    required this.id,
    required this.name,
    required this.baseCurrency,
    required this.role,
  });

  factory Household.fromJson(JsonMap json) => Household(
    id: json.string('id'),
    name: json.string('name'),
    baseCurrency: json.string('baseCurrency'),
    role: Role.parse(json.string('role')),
  );

  final String id;
  final String name;
  final String baseCurrency;
  final Role role;
}

/// One member. [id] is the **membership** id — what the role and removal endpoints take, not the
/// user id.
final class Member {
  const Member({
    required this.id,
    required this.userId,
    required this.email,
    required this.displayName,
    required this.role,
    required this.joinedAt,
  });

  factory Member.fromJson(JsonMap json) => Member(
    id: json.string('id'),
    userId: json.string('userId'),
    email: json.string('email'),
    displayName: json.string('displayName'),
    role: Role.parse(json.string('role')),
    joinedAt: DateTime.parse(json.string('joinedAt')),
  );

  final String id;
  final String userId;
  final String email;
  final String displayName;
  final Role role;
  final DateTime joinedAt;
}

/// What the member list offers each row, by the rules the server enforces: an owner changes
/// anyone's role but their own and removes anyone but themselves; anyone may leave; the only owner
/// may do neither. **A courtesy, never the control** — the API refuses what this should not have
/// offered, and the screen shows its translated refusal.
enum MemberControls {
  /// An owner looking at somebody else: a role picker and Remove.
  manage,

  /// The caller's own row, and they may leave.
  leave,

  /// The caller's own row as the only owner: no way out until somebody else is an owner.
  soleOwner,

  /// Somebody else, seen by a member or a viewer: their role, as a label.
  none,
}

MemberControls controlsFor(
  Member member, {
  required String myMembershipId,
  required bool iAmOwner,
  required List<Member> all,
}) {
  final mine = member.id == myMembershipId;
  if (!mine) return iAmOwner ? MemberControls.manage : MemberControls.none;
  final owners = all.where((m) => m.role == Role.owner).length;
  return member.role == Role.owner && owners == 1 ? MemberControls.soleOwner : MemberControls.leave;
}

/// A new invitation, from `POST /api/households/current/invitations`.
///
/// **The token in [acceptPath] is a credential**: possession of the link is all that joining needs. It exists in
/// this one response only (the server keeps a hash), so it is held in the invite sheet's memory
/// while the sheet is open and dropped when it closes — never cached, stored or logged, and never
/// given a [toString] that could print it.
final class Invitation {
  const Invitation({
    required this.id,
    required this.email,
    required this.role,
    required this.acceptPath,
    required this.expiresAt,
  });

  factory Invitation.fromJson(JsonMap json) => Invitation(
    id: json.string('id'),
    email: json.string('email'),
    role: Role.parse(json.string('role')),
    acceptPath: json.string('acceptPath'),
    expiresAt: DateTime.parse(json.string('expiresAt')),
  );

  final String id;
  final String email;
  final Role role;

  /// `/join/<token>`: the relative half of the link, which carries the token.
  final String acceptPath;
  final DateTime expiresAt;

  @override
  String toString() => 'Invitation($id)';
}

/// The absolute invitation link, built from **the server this phone is connected to**.
///
/// The server returns a relative `acceptPath` on purpose: the only origin it knows is the `Host`
/// header, which the caller chooses, and a credential-bearing URL assembled from it would be a
/// phishing link with our name on it (authentication-and-households.md). The origin the person
/// typed and the phone verified is the right one — the web uses its own window's for the same
/// reason.
///
/// [acceptPath] must stay on that origin: a path that is really a protocol-relative or absolute
/// URL (`//evil.test/…`, `https://evil.test/…`) is refused, and so is anything but `/join/<token>`.
/// Null when the link cannot be made safely; the sheet then says so rather than guessing.
String? buildInviteLink(String acceptPath, ServerAddress server) {
  if (!acceptPath.startsWith('/') || acceptPath.startsWith('//')) return null;
  final Uri link;
  try {
    link = server.origin.resolve(acceptPath);
  } on FormatException {
    return null;
  }
  if (link.origin != server.origin.origin) return null;
  if (link.hasQuery || link.hasFragment) return null;
  final segments = link.pathSegments;
  if (segments.length != 2 || segments.first != 'join' || segments[1].isEmpty) return null;
  return link.toString();
}
