import 'package:flowdux_flutter/flowdux_flutter.dart';

class FetchRandomNumberAction implements Action {}

class FetchStartedAction implements Action {}

class FetchSuccessAction implements Action {
  final int value;

  FetchSuccessAction(this.value);
}

class FetchErrorAction implements Action {
  final String error;

  FetchErrorAction(this.error);
}
