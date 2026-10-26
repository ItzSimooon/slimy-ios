import SwiftUI

/// Answers to common questions, the same as on slime.hyprnode.de/support,
/// with notes that depend on how Slimy was installed on this iPhone.
struct HelpView: View {
    @State private var query = ""
    @State private var expanded: Set<String> = []

    private let topics = HelpTopic.all(sideloaded: HelpInstall.isSideloaded, hasAppGroup: HelpInstall.hasAppGroup)

    var body: some View {
        let shown = filtered
        List {
            ForEach(shown) { topic in
                Section(topic.title) {
                    ForEach(topic.items) { item in
                        DisclosureGroup(isExpanded: isExpanded(item.id)) {
                            Text(item.answer)
                                .font(.subheadline)
                                .foregroundStyle(Theme.inkSoft)
                                .fixedSize(horizontal: false, vertical: true)
                                .padding(.vertical, 4)
                                .textSelection(.enabled)
                        } label: {
                            Text(item.question)
                                .font(.body.weight(.medium))
                                .foregroundStyle(Theme.ink)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            }
            if !shown.isEmpty {
                Section {
                    SettingsLinkRow(title: "Contact Support", systemImage: "envelope", url: FriendsStore.supportURL)
                } footer: {
                    Text("More help and ways to reach us are on slime.hyprnode.de/support.")
                }
            }
        }
        .overlay {
            if shown.isEmpty {
                ContentUnavailableView.search(text: query)
            }
        }
        .navigationTitle("Help")
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search help")
        .onChange(of: query) { _, _ in
            // Open every answer that matches, so the result is visible at once.
            if !trimmedQuery.isEmpty {
                expanded = Set(filtered.flatMap { $0.items.map(\.id) })
            }
        }
    }

    private var trimmedQuery: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var filtered: [HelpTopic] {
        let text = trimmedQuery
        guard !text.isEmpty else { return topics }
        return topics.compactMap { topic -> HelpTopic? in
            let items = topic.items.filter {
                $0.question.localizedCaseInsensitiveContains(text) || $0.answer.localizedCaseInsensitiveContains(text)
            }
            return items.isEmpty ? nil : HelpTopic(id: topic.id, title: topic.title, items: items)
        }
    }

    private func isExpanded(_ id: String) -> Binding<Bool> {
        Binding(
            get: { expanded.contains(id) },
            set: { open in
                if open {
                    expanded.insert(id)
                } else {
                    expanded.remove(id)
                }
            })
    }
}

/// How Slimy got onto this iPhone, for answers that depend on it.
private enum HelpInstall {
    /// App Store and TestFlight builds carry no provisioning profile;
    /// sideloaded and developer builds do.
    static var isSideloaded: Bool {
        Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision") != nil
    }

    /// The App Group lets the widgets read the save.
    static var hasAppGroup: Bool {
        GameStorage.sharedDirectory != nil
    }
}

private struct HelpItem: Identifiable {
    let id: String
    let question: String
    let answer: String
}

private struct HelpTopic: Identifiable {
    let id: String
    let title: String
    let items: [HelpItem]

    static func all(sideloaded: Bool, hasAppGroup: Bool) -> [HelpTopic] {
        [island, install(sideloaded: sideloaded, hasAppGroup: hasAppGroup), friends, chat, data]
    }

    private static let island = HelpTopic(id: "island", title: "Dynamic Island", items: [
        HelpItem(
            id: "island.gone",
            question: "My slime disappeared from the Dynamic Island.",
            answer: "iOS ends every Live Activity after about 8 hours and doesn't let apps start a new one in the background. Open Slimy and your slime comes right back. Without opening the app, use Summon Slime from Control Center, the Action Button, Siri or the Shortcuts app. While it's still there, tapping one of its buttons in the island renews it when its time is nearly up. If it still doesn't appear, check that Live Activities are on for Slimy in the Settings app, and that Always bring it back is on in Slimy's Settings."),
        HelpItem(
            id: "island.summon",
            question: "How do I add Summon Slime to Control Center or the Action Button?",
            answer: "Control Center: open it, touch and hold an empty spot, tap Add a Control and choose Summon Slime. Action Button, on iPhones that have one: open the Settings app, tap Action Button, swipe to Controls and pick Summon Slime."),
        HelpItem(
            id: "island.still",
            question: "The slime in the island doesn't move.",
            answer: "Check that Animation is on in Slimy's Settings. With Animation off, the island shows a still picture that changes with your slime's mood."),
        HelpItem(
            id: "island.late",
            question: "Why does my slime react late to my iPhone?",
            answer: "While Slimy is open it reacts right away. When it's closed, it notices changes the next time iOS lets it run, for example during background refresh or while charging, so a reaction can come a little later. Make sure Background App Refresh is on for Slimy in the Settings app."),
    ])

    private static func install(sideloaded: Bool, hasAppGroup: Bool) -> HelpTopic {
        var widgets = "Open Slimy once so the widgets get your slime, then give them a moment. iOS decides how often widgets refresh."
        if !hasAppGroup {
            widgets += " On this iPhone, Slimy runs without its App Group, so the widgets can't see your slime. This happens with Slimy-unsigned.ipa or when a sideloading tool removes the App Group. Install Slimy.ipa instead to use widgets."
        }
        var items: [HelpItem] = []
        if sideloaded {
            items.append(HelpItem(
                id: "install.resign",
                question: "Slimy won't open anymore.",
                answer: "With a free Apple ID, sideloaded apps stop opening after 7 days until they're signed again. Connect your iPhone to your computer, open Sideloadly and install the newest Slimy.ipa with the same Apple ID. Your slime stays as long as you don't delete the app first. Exporting a save now and then, in Settings under Backup, keeps you on the safe side."))
            items.append(HelpItem(
                id: "install.update",
                question: "How do I update Slimy?",
                answer: "New versions are on slime.hyprnode.de. Install the new Slimy.ipa over the current app the same way you installed it, with the same Apple ID, and your slime stays."))
        }
        items.append(HelpItem(id: "install.widgets", question: "My widgets stay empty.", answer: widgets))
        return HelpTopic(id: "install", title: sideloaded ? "Install and widgets" : "Widgets", items: items)
    }

    private static let friends = HelpTopic(id: "friends", title: "Friends", items: [
        HelpItem(
            id: "friends.add",
            question: "How do I add a friend?",
            answer: "Open Friends and go online. Then share your code, or enter your friend's code. A code works once and expires after 10 minutes, so if one doesn't work, ask for a new one."),
        HelpItem(
            id: "friends.love",
            question: "How does love work?",
            answer: "Every friendship has a love meter from 0 to 100 that you both share. Hugs, visits and gifts fill it, and kisses unlock at 50. Each kind counts a few times a day, so a little every day goes furthest. At 100 your slimes fall in love for good."),
        HelpItem(
            id: "friends.strangers",
            question: "Can strangers find me?",
            answer: "No. There's no search and no public profile. Only people you give a code to can send you a friend request, and you decide whether to accept it."),
        HelpItem(
            id: "friends.report",
            question: "How do I report or block someone?",
            answer: "In Friends, tap a friend and choose Report or Block at the bottom of their page, or touch and hold them in the list. For a friend request, tap the three dots next to it. Blocking ends the friendship right away, nothing from that device reaches you anymore, and they aren't notified. You can unblock them later under Privacy & blocked in Friends. A report goes to the Slimy team for review and doesn't block anyone by itself, so block them too if you don't want to hear from them again. To report something your slime said in chat, touch and hold the reply."),
    ])

    private static let chat = HelpTopic(id: "chat", title: "Talking with your slime", items: [
        HelpItem(
            id: "chat.data",
            question: "What happens to my chat messages?",
            answer: "Nothing is sent until you agree in the chat before your first message. Then your message, the recent chat and a few facts about your slime, like its name, mood and needs, go to Cloudflare Workers AI and Groq, which write the reply. Your name and device ID are never passed on to them, and slime.hyprnode.de doesn't keep the messages. The chat itself stays on this iPhone. You can turn Talk with AI off any time in Settings under Privacy, or under About AI Chat in the chat menu."),
        HelpItem(
            id: "chat.sleepy",
            question: "My slime is too sleepy to talk.",
            answer: "Talking uses a limited amount of AI each day. When it runs out, or the AI services are busy, your slime gets sleepy. Try again later."),
    ])

    private static let data = HelpTopic(id: "data", title: "Your data", items: [
        HelpItem(
            id: "data.delete",
            question: "How do I delete my data?",
            answer: "Your slime, coins and things live only on this iPhone. If you went online, slime.hyprnode.de stores your online profile, your friends and what they sent you. To delete that, go to Settings, then Privacy, and tap Delete My Data on the Server. You'll find the same button in Friends under Privacy & blocked. To remove your slime from this iPhone, use Start over in Settings."),
        HelpItem(
            id: "data.move",
            question: "How do I move my slime to a new iPhone?",
            answer: "In Settings under Backup, tap Export Save and keep the file in Files or AirDrop it to the new iPhone. After hatching on the new iPhone, open Settings and tap Import Save; your old slime replaces the new one. Friends aren't part of a save, so add them again after going online."),
        HelpItem(
            id: "data.cost",
            question: "Does Slimy cost anything?",
            answer: "No. There are no purchases and no ads. Coins are earned in the app, by playing and from daily gifts, and can't be bought."),
    ])
}
