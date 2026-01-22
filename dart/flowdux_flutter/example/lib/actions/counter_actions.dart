import 'package:flowdux_flutter/flowdux_flutter.dart';

class IncrementAction implements Action {}

class DecrementAction implements Action {}

class AddAction implements Action {
  final int value;

  AddAction(this.value);
}

class SetCountAction implements Action {
  final int value;

  SetCountAction(this.value);
}
