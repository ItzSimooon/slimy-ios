import AppIntents
import SwiftUI
import WidgetKit

@main
struct SlimeWidgetBundle: WidgetBundle {
    var body: some Widget {
        SlimeLiveActivity()
        SummonSlimeControl()
        FeedSlimeControl()
    }
}

/// Control Center / Lock Screen / Action Button: bring the slime back.
struct SummonSlimeControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "de.hyprnode.slime.summon") {
            ControlWidgetButton(action: SummonSlimeIntent()) {
                Label("Summon Slime", systemImage: "drop.fill")
            }
        }
        .displayName("Summon Slime")
        .description("Puts your slime back in the Dynamic Island.")
    }
}

struct FeedSlimeControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "de.hyprnode.slime.feed") {
            ControlWidgetButton(action: FeedSlimeIntent()) {
                Label("Feed Slime", systemImage: "fork.knife")
            }
        }
        .displayName("Feed Slime")
        .description("Feeds your slime without opening the app.")
    }
}
