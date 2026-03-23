import 'dart:io';

import 'package:flowdux/flowdux.dart';

// State
class CounterState {
  final int count;
  const CounterState([this.count = 0]);

  CounterState copyWith({int? count}) => CounterState(count ?? this.count);

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is CounterState && count == other.count;

  @override
  int get hashCode => count.hashCode;

  @override
  String toString() => 'CounterState(count: $count)';
}

// Actions
abstract class CounterAction implements Action {}

class IncrementAction implements CounterAction {}

class DecrementAction implements CounterAction {}

// Reducer
CounterState counterReducer(CounterState state, CounterAction action) {
  if (action is IncrementAction) {
    return state.copyWith(count: state.count + 1);
  } else if (action is DecrementAction) {
    return state.copyWith(count: state.count - 1);
  }
  return state;
}

void main() async {
  final store = createTimeTravelStore<CounterState, CounterAction>(
    initialState: CounterState(),
    reducer: counterReducer,
  );

  store.state.listen((state) {
    // State changes are printed by the command handler
  });

  print('=== TimeTravelStore Sample ===');
  print('Commands:');
  print('  +       Increment counter');
  print('  -       Decrement counter');
  print('  u       Undo');
  print('  r       Redo');
  print('  j <n>   Jump to history index n');
  print('  h       Show history');
  print('  c       Clear history');
  print('  q       Quit');
  print('');

  _printState(store);

  while (true) {
    stdout.write('> ');
    final input = stdin.readLineSync()?.trim();
    if (input == null || input == 'q') {
      print('Goodbye!');
      break;
    }

    if (input.isEmpty) continue;

    switch (input) {
      case '+':
        store.dispatch(IncrementAction());
        await Future.delayed(Duration(milliseconds: 50));
        _printState(store);
      case '-':
        store.dispatch(DecrementAction());
        await Future.delayed(Duration(milliseconds: 50));
        _printState(store);
      case 'u':
        final success = await store.undo();
        if (success) {
          _printState(store);
        } else {
          print('  Cannot undo (at beginning of history)');
        }
      case 'r':
        final success = await store.redo();
        if (success) {
          _printState(store);
        } else {
          print('  Cannot redo (at end of history)');
        }
      case 'h':
        _printHistory(store);
      case 'c':
        await store.clear();
        print('  History cleared');
        _printState(store);
      default:
        if (input.startsWith('j ')) {
          final indexStr = input.substring(2).trim();
          final index = int.tryParse(indexStr);
          if (index == null) {
            print('  Invalid index: $indexStr');
          } else {
            final success = await store.jumpTo(index);
            if (success) {
              _printState(store);
            } else {
              print('  Invalid index: $index (valid: 0..${store.history.length - 1})');
            }
          }
        } else {
          print('  Unknown command: $input');
        }
    }
  }

  await store.close();
}

void _printState(TimeTravelStore<CounterState, CounterAction> store) {
  final undo = store.canUndo ? 'Y' : 'N';
  final redo = store.canRedo ? 'Y' : 'N';
  print(
    '  count: ${store.currentState.count}'
    '  [index: ${store.currentIndex}/${store.history.length - 1}]'
    '  undo: $undo  redo: $redo',
  );
}

void _printHistory(TimeTravelStore<CounterState, CounterAction> store) {
  print('  History (${store.history.length} entries):');
  for (final snapshot in store.history) {
    final marker = snapshot.index == store.currentIndex ? ' <<' : '';
    final action =
        snapshot.action != null ? snapshot.action.runtimeType.toString() : '-';
    print(
      '    [${snapshot.index}] count: ${snapshot.currentState.count}'
      '  action: $action$marker',
    );
  }
}
