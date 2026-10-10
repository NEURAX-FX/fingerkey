/// Manual pairing over TCP: enter the server endpoint, TLS certificate
/// fingerprint, and one-time token printed by `fingerkey pair-code`. The
/// existing DaemonClient sends this phone's Ed25519 public key in `hello`.
library;

import 'dart:async';

import 'package:flutter/material.dart';

import 'daemon_client.dart';
import 'key_store.dart';

String _formatHostPort(String host, int port) {
  final needsBrackets = host.contains(':') && !host.startsWith('[');
  return '${needsBrackets ? '[$host]' : host}:$port';
}

Future<RosterComputer> pairFromManual({
  required String host,
  required int port,
  required String fingerprint,
  required String token,
  required String name,
  required KeyStore keyStore,
  required DaemonClient Function() clientFactory,
}) async {
  final cleanHost = host.trim();
  final cleanFingerprint = fingerprint.trim();
  final cleanToken = token.trim();
  if (cleanHost.isEmpty || port < 1 || port > 65535) {
    throw const FormatException('server and port are required');
  }
  if (cleanFingerprint.isEmpty || cleanToken.isEmpty) {
    throw const FormatException(
      'TLS fingerprint and pairing token are required',
    );
  }

  final client = clientFactory();
  try {
    final welcome = await client
        .connect(
          host: cleanHost,
          port: port,
          fp: cleanFingerprint,
          token: cleanToken,
        )
        .timeout(const Duration(seconds: 10));
    if (!welcome.registered) {
      throw const PairRejected('connection refused (key not accepted)');
    }
  } finally {
    try {
      await client.dispose();
    } catch (_) {
      // The key is already registered on the daemon; teardown is irrelevant.
    }
  }

  final computer = RosterComputer(
    name: name.trim().isEmpty ? cleanHost : name.trim(),
    fingerprint: cleanFingerprint,
    lastAddr: _formatHostPort(cleanHost, port),
  );
  await keyStore.addComputer(
    name: computer.name,
    fingerprint: computer.fingerprint,
    lastAddr: computer.lastAddr,
  );
  return computer;
}

class PairRejected implements Exception {
  const PairRejected(this.message);
  final String message;
  @override
  String toString() => message;
}

class PairingScreen extends StatefulWidget {
  const PairingScreen({
    super.key,
    required this.keyStore,
    required this.identity,
    required this.onPaired,
    this.clientFactory,
  });

  final KeyStore keyStore;
  final DeviceIdentity identity;
  final void Function(DeviceIdentity identity) onPaired;
  final DaemonClient Function()? clientFactory;

  @override
  State<PairingScreen> createState() => PairingScreenState();
}

class PairingScreenState extends State<PairingScreen> {
  final _host = TextEditingController();
  final _port = TextEditingController(text: '4443');
  final _fingerprint = TextEditingController();
  final _token = TextEditingController();
  final _name = TextEditingController();
  bool _pairing = false;
  bool _paired = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    debugPrint('phone-fprint-auth pubkey: ${widget.identity.publicKeyBase64}');
  }

  @override
  void dispose() {
    _host.dispose();
    _port.dispose();
    _fingerprint.dispose();
    _token.dispose();
    _name.dispose();
    super.dispose();
  }

  DaemonClient _client() =>
      widget.clientFactory?.call() ??
      DaemonClient(pubkey: widget.identity.publicKeyBase64);

  Future<void> pair() async {
    if (_pairing || _paired) return;
    final port = int.tryParse(_port.text.trim());
    if (port == null) {
      setState(() => _error = 'Port must be a number.');
      return;
    }
    setState(() {
      _pairing = true;
      _error = null;
    });
    try {
      await pairFromManual(
        host: _host.text,
        port: port,
        fingerprint: _fingerprint.text,
        token: _token.text,
        name: _name.text,
        keyStore: widget.keyStore,
        clientFactory: _client,
      );
      if (!mounted) return;
      _paired = true;
      widget.onPaired(widget.identity);
    } catch (e) {
      debugPrint('phone-fprint-auth: pairing failed: $e');
      if (mounted) setState(() => _error = 'Pairing failed: $e');
    } finally {
      if (mounted) setState(() => _pairing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('PAIR DEVICE')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            Text('ENTER SERVER DETAILS', style: theme.textTheme.titleLarge),
            const SizedBox(height: 8),
            const Text(
              'Run `sudo fingerkey pair-code <name>` on the computer, then '
              'copy its fields here. Your device key is sent automatically.',
            ),
            const SizedBox(height: 20),
            _field(_host, 'Server / IP', 'frp-arm.com'),
            _field(_port, 'Port', '19230', keyboard: TextInputType.number),
            _field(
              _fingerprint,
              'TLS fingerprint',
              '64 lowercase hex characters',
            ),
            _field(_token, 'One-time pairing token', 'expires in 60 seconds'),
            _field(_name, 'Computer name (optional)', 'my-computer'),
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: _pairing ? null : pair,
              icon: _pairing
                  ? const SizedBox.square(
                      dimension: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.link),
              label: Text(_pairing ? 'PAIRING…' : 'PAIR OVER TCP'),
            ),
            if (_error != null) ...[
              const SizedBox(height: 12),
              Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
            ],
            const SizedBox(height: 24),
            Text(
              'This device key (sent automatically):',
              style: theme.textTheme.labelSmall,
            ),
            const SizedBox(height: 4),
            SelectableText(
              widget.identity.publicKeyBase64,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
            ),
          ],
        ),
      ),
    );
  }

  Widget _field(
    TextEditingController controller,
    String label,
    String hint, {
    TextInputType? keyboard,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: TextField(
        controller: controller,
        keyboardType: keyboard,
        autocorrect: false,
        enableSuggestions: false,
        decoration: InputDecoration(
          border: const OutlineInputBorder(),
          labelText: label,
          hintText: hint,
        ),
      ),
    );
  }
}
