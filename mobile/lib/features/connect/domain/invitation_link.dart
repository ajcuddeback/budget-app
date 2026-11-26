import 'package:budget_owl/core/server/server_address.dart';

/// What a pasted invitation link turned out to be.
sealed class InvitationLink {
  const InvitationLink();
}

/// A link for this server. Only [token] is kept from it, and only in memory for the join screen.
final class InvitationForThisServer extends InvitationLink {
  const InvitationForThisServer(this.token);
  final String token;
}

/// A link to some other host. **Refused rather than followed**: switching server on the strength
/// of a pasted link would let a link choose where the person's next password goes (mobile-app.md,
/// attack 8).
final class InvitationForAnotherServer extends InvitationLink {
  const InvitationForAnotherServer(this.host);
  final String host;
}

final class NotAnInvitation extends InvitationLink {
  const NotAnInvitation();
}

final RegExp _token = RegExp(r'^[A-Za-z0-9_-]{16,256}$');

/// Reads a pasted link: `https://<this server>/join/<token>`, as the web composes it from its own
/// origin and the invitation's relative `acceptPath`.
///
/// The scheme, host and port must all be this server's. Nothing else in the link is used.
InvitationLink parseInvitationLink(String pasted, ServerAddress server) {
  final uri = Uri.tryParse(pasted.trim());
  if (uri == null || !uri.hasScheme || uri.host.isEmpty) return const NotAnInvitation();
  final segments = uri.pathSegments.where((s) => s.isNotEmpty).toList();
  if (segments.length != 2 || segments.first != 'join' || !_token.hasMatch(segments[1])) {
    return const NotAnInvitation();
  }
  if (uri.scheme.toLowerCase() != server.origin.scheme ||
      uri.host.toLowerCase() != server.origin.host ||
      uri.port != server.origin.port) {
    return InvitationForAnotherServer(uri.hasPort ? '${uri.host}:${uri.port}' : uri.host);
  }
  return InvitationForThisServer(segments[1]);
}
