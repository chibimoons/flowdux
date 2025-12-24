import SwiftUI
import shared

struct ContentView: View {
    @StateObject private var viewModel = CounterViewModel()

    var body: some View {
        VStack(spacing: 24) {
            Text("Flowdux KMM")
                .font(.largeTitle)
                .fontWeight(.bold)

            Text("Shared Counter")
                .font(.subheadline)
                .foregroundColor(.secondary)

            Spacer().frame(height: 32)

            Text("\(viewModel.count)")
                .font(.system(size: 72, weight: .bold))
                .foregroundColor(.blue)

            Spacer().frame(height: 32)

            HStack(spacing: 16) {
                Button(action: { viewModel.decrement() }) {
                    Text("-")
                        .font(.title)
                        .frame(width: 64, height: 64)
                        .background(Color.blue)
                        .foregroundColor(.white)
                        .cornerRadius(8)
                }

                Button(action: { viewModel.increment() }) {
                    Text("+")
                        .font(.title)
                        .frame(width: 64, height: 64)
                        .background(Color.blue)
                        .foregroundColor(.white)
                        .cornerRadius(8)
                }
            }

            HStack(spacing: 8) {
                Button("+10") { viewModel.add(value: 10) }
                    .buttonStyle(.bordered)
                Button("-10") { viewModel.add(value: -10) }
                    .buttonStyle(.bordered)
            }

            Button("Reset") { viewModel.reset() }
                .buttonStyle(.bordered)
        }
        .padding()
    }
}

class CounterViewModel: ObservableObject {
    private let store: CounterStore
    private var watcher: Closeable?
    @Published var count: Int = 0

    init() {
        store = CounterStore(scope: MainScope())

        // Observe state changes using watchState
        watcher = store.watchState { [weak self] state in
            DispatchQueue.main.async {
                self?.count = Int(state.count)
            }
        }
    }

    deinit {
        watcher?.close()
    }

    func increment() { store.increment() }
    func decrement() { store.decrement() }
    func add(value: Int) { store.add(value: Int32(value)) }
    func reset() { store.reset() }
}

#Preview {
    ContentView()
}
