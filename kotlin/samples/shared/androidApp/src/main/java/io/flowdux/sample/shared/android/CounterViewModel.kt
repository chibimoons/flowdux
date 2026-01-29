package io.flowdux.sample.shared.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.flowdux.sample.shared.CounterStore

class CounterViewModel : ViewModel() {
    val store = CounterStore(viewModelScope)
}
