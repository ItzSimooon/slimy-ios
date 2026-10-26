import SwiftUI

/// Reporting a person to the Slimy team: a reason, an optional note, and an
/// optional block, which is a separate step on purpose.
struct FriendsReportSheet: View {
    let target: ReportTarget

    @EnvironmentObject private var friends: FriendsStore
    @Environment(\.dismiss) private var dismiss
    @FocusState private var noteFocused: Bool

    @State private var reason: ReportReason = .name
    @State private var note = ""
    @State private var alsoBlock = false
    @State private var sending = false
    @State private var error: String?
    @State private var sent = false
    @State private var blockProblem: String?

    var body: some View {
        NavigationStack {
            Group {
                if sent {
                    confirmation
                } else {
                    form
                }
            }
            .navigationTitle("Report")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbarItems }
            .interactiveDismissDisabled(sending)
        }
    }

    // MARK: Form

    private var form: some View {
        Form {
            Section {
                Picker("Reason", selection: $reason) {
                    ForEach(ReportReason.forPeople) { reason in
                        Text(reason.title).tag(reason)
                    }
                }
                .pickerStyle(.inline)
                .labelsHidden()
            } header: {
                Text("What's wrong with \(target.ownerName)?")
            }

            Section {
                TextField("What happened? (optional)", text: $note, axis: .vertical)
                    .lineLimit(3...6)
                    .focused($noteFocused)
                    .onChange(of: note) { _, new in
                        if new.count > HyprnodeAPI.reportNoteLimit {
                            note = String(new.prefix(HyprnodeAPI.reportNoteLimit))
                        }
                    }
            } header: {
                Text("Details")
            } footer: {
                Text("\(note.count) of \(HyprnodeAPI.reportNoteLimit) characters")
                    .monospacedDigit()
            }

            Section {
                Toggle("Also block \(target.ownerName)", isOn: $alsoBlock)
            } footer: {
                Text("Reports go to the Slimy team. A person reviews each one and can reset names or ban a device. \(target.ownerName) isn't told who reported them. A report doesn't block anyone; turn on Block to stop hearing from them right away.")
            }

            if let error {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(Theme.danger)
                }
            }
        }
    }

    // MARK: Sent

    private var confirmation: some View {
        ContentUnavailableView {
            Label("Thanks for telling us", systemImage: "checkmark.circle.fill")
                .foregroundStyle(Theme.accent)
        } description: {
            VStack(spacing: 8) {
                Text("The Slimy team will look at your report. Thank you for helping keep Slimy friendly.")
                if alsoBlock {
                    if let blockProblem {
                        Text("Blocking didn't work: \(blockProblem) You can block \(target.ownerName) from the friends list.")
                            .foregroundStyle(Theme.danger)
                    } else {
                        Text("\(target.ownerName) is blocked.")
                    }
                }
            }
        } actions: {
            AppButton(title: "Done", compact: true) { dismiss() }
        }
    }

    @ToolbarContentBuilder
    private var toolbarItems: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            if !sent {
                Button("Cancel") { dismiss() }
                    .disabled(sending)
            }
        }
        ToolbarItem(placement: .confirmationAction) {
            if sending {
                ProgressView()
                    .accessibilityLabel("Sending")
            } else if !sent {
                Button("Send") { send() }
                    .fontWeight(.semibold)
            }
        }
    }

    private func send() {
        noteFocused = false
        error = nil
        sending = true
        let blocking = alsoBlock
        Task {
            let ok = await friends.report(deviceID: target.deviceID, reason: reason, note: note, text: nil)
            guard ok else {
                sending = false
                error = friends.lastError ?? "The report couldn't be sent. Please try again."
                friends.lastError = nil
                return
            }
            if blocking {
                let blocked = await friends.block(deviceID: target.deviceID)
                if !blocked {
                    blockProblem = friends.lastError ?? "Please try again."
                    friends.lastError = nil
                }
            }
            sending = false
            Feedback.shared.play(.success)
            sent = true
        }
    }
}
