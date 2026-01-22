import 'package:flowdux_flutter/flowdux_flutter.dart';

class ShowMessageAction implements Action {
  final String message;

  ShowMessageAction(this.message);
}

class ClearMessageAction implements Action {}
