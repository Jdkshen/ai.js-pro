import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';

import '../lib/bridge.dart';

void main() {
  for (final method in ['share', 'shareFile', 'tasks']) {
    testWidgets('$method keeps the appropriate response timeout', (
      tester,
    ) async {
      final response = Completer<String>();
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockDecodedMessageHandler<String>(
        LocalBridge.channel,
        (_) => response.future,
      );
      addTearDown(
        () => messenger.setMockDecodedMessageHandler<String>(
          LocalBridge.channel,
          null,
        ),
      );

      Object? error;
      dynamic value;
      LocalBridge.call(method, {'path': '/scripts/project'}).then(
        (result) => value = result,
        onError: (Object failure) => error = failure,
      );
      await tester.pump(const Duration(seconds: 25));

      if (method == 'tasks') {
        expect(error, isA<TimeoutException>());
      } else {
        expect(error, isNull);
      }
      response.complete(jsonEncode({'ok': true, 'data': true}));
      await tester.pump();
      if (method != 'tasks') expect(value, isTrue);
    });
  }
}
