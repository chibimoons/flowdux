import 'package:flowdux_flutter/flowdux_flutter.dart';

class SearchAction implements Action {
  final String query;

  SearchAction(this.query);
}

class SearchResultsAction implements Action {
  final String query;
  final List<String> results;

  SearchResultsAction(this.query, this.results);
}
