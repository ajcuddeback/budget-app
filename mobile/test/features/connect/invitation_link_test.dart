import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/features/connect/domain/invitation_link.dart';
import 'package:flutter_test/flutter_test.dart';

/// A pasted invitation link for another host is refused, never followed (attack 8).
void main() {
  final server = (ServerAddress.parse(
    'owl.example.test',
    allowLocalCleartext: false,
  ) as AddressAccepted).address;

  test('takes only the token from a link to this server', () {
    final link = parseInvitationLink('https://owl.example.test/join/fixture-invitation', server);
    expect((link as InvitationForThisServer).token, 'fixture-invitation');
  });

  test('refuses a link to another host, naming it', () {
    final link = parseInvitationLink('https://evil.example.test/join/fixture-invitation', server);
    expect((link as InvitationForAnotherServer).host, 'evil.example.test');
  });

  test('refuses the same host on another port, or over http', () {
    expect(
      parseInvitationLink('https://owl.example.test:8443/join/fixture-invitation', server),
      isA<InvitationForAnotherServer>(),
    );
    expect(
      parseInvitationLink('http://owl.example.test/join/fixture-invitation', server),
      isA<InvitationForAnotherServer>(),
    );
  });

  test('refuses what is not an invitation link', () {
    for (final text in [
      '',
      'fixture-invitation',
      'https://owl.example.test/',
      'https://owl.example.test/join/',
      'https://owl.example.test/join/short',
      'https://owl.example.test/login/fixture-invitation',
      'https://owl.example.test/join/fixture-invitation/extra',
      'https://owl.example.test/join/bad%20token%20with%20spaces',
    ]) {
      expect(parseInvitationLink(text, server), isA<NotAnInvitation>(), reason: text);
    }
  });
}
