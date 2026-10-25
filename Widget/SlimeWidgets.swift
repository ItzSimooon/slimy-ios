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
                Label("Slime holen", systemImage: "drop.fill")
            }
        }
        .displayName("Slime holen")
        .description("Holt deinen Slime zurück in die Dynamic Island.")
    }
}

struct FeedSlimeControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "de.hyprnode.slime.feed") {
            ControlWidgetButton(action: FeedSlimeIntent()) {
                Label("Slime füttern", systemImage: "fork.knife")
            }
        }
        .displayName("Slime füttern")
        .description("Füttert deinen Slime, ohne die App zu öffnen.")
    }
}
