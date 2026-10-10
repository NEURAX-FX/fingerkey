/// Tests for manual TCP pairing: endpoint, fingerprint, token, and the
/// device public key sent by the app.
library;

// ignore: depend_on_referenced_packages
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'package:auth/daemon_client.dart';
import 'package:auth/key_store.dart';
import 'package:auth/pairing_screen.dart';
import 'package:auth/tcp_tls_link.dart';

class _NoopLink extends TcpTlsLink {
  @override
  Stream<TcpEvent> get events => Stream<TcpEvent>.empty();
}

class _FakeClient extends DaemonClient {
  _FakeClient() : super(pubkey: 'PUBKEY', link: _NoopLink());
  String? host;
  int? port;
  String? fp;
  String? token;

  @override
  bool registered = true;

  @override
  Future<Welcome> connect({
    required String host,
    required int port,
    required String fp,
    String? token,
  }) async {
    this.host = host;
    this.port = port;
    this.fp = fp;
    this.token = token;
    return Welcome(registered: registered, key: 'phone', pending: const []);
  }

  @override
  Future<void> dispose() async {}
}

void main() {
  test('manual pairing connects and persists the public endpoint', () async {
    // ignore: invalid_use_of_visible_for_testing_member
    FlutterSecureStorage.setMockInitialValues({});
    final keyStore = KeyStore();
    final client = _FakeClient();
    final added = await pairFromManual(
      host: 'frp-arm.com',
      port: 19230,
      fingerprint: 'beef',
      token: 'abc123',
      name: 'remote',
      keyStore: keyStore,
      clientFactory: () => client,
    );
    expect(client.host, 'frp-arm.com');
    expect(client.port, 19230);
    expect(client.fp, 'beef');
    expect(client.token, 'abc123');
    expect(added.lastAddr, 'frp-arm.com:19230');
  });

  test('manual pairing rejects an unregistered key', () async {
    // ignore: invalid_use_of_visible_for_testing_member
    FlutterSecureStorage.setMockInitialValues({});
    await expectLater(
      pairFromManual(
        host: 'frp-arm.com',
        port: 19230,
        fingerprint: 'beef',
        token: 'abc123',
        name: 'remote',
        keyStore: KeyStore(),
        clientFactory: () => (_FakeClient()..registered = false),
      ),
      throwsA(isA<PairRejected>()),
    );
  });
}
