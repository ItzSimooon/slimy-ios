import AppIntents
import SwiftUI
import WidgetKit

@main
struct SlimeWidgetBundle: WidgetBundle {
    var body: some Widget {
        SlimeLiveActivity()
        SlimeHomeWidget()
        SlimeNeedsWidget()
        SummonSlimeControl()
        FeedSlimeControl()
        PetSlimeControl()
        BedtimeControl()
    }
}

// Control Center, the Lock Screen and the Action Button. Each control runs
// its intent in the app's process without opening the app.

/// Brings the slime back to the Dynamic Island.
struct SummonSlimeControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "de.hyprnode.slime.summon") {
            ControlWidgetButton(action: SummonSlimeIntent()) {
                Label("Summon Slime", systemImage: "drop.fill")
            }
        }
        .displayName("Summon Slime")
        .description("Brings your slime back to the Dynamic Island.")
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

struct PetSlimeControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "de.hyprnode.slime.pet") {
            ControlWidgetButton(action: PetSlimeIntent()) {
                Label("Pet Slime", systemImage: "hand.raised.fill")
            }
        }
        .displayName("Pet Slime")
        .description("Pets your slime without opening the app.")
    }
}

struct BedtimeControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "de.hyprnode.slime.bedtime") {
            ControlWidgetButton(action: SleepSlimeIntent()) {
                Label("Slime Bedtime", systemImage: "moon.fill")
            }
        }
        .displayName("Slime Bedtime")
        .description("Puts your slime to bed, or wakes it up if it's asleep.")
    }
}
