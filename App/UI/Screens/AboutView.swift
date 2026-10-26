import SwiftUI
import UIKit

/// Version, who makes Slimy, links, licenses and acknowledgements.
struct AboutView: View {
    @EnvironmentObject private var store: GameStore
    @State private var showWhatsNew = false

    private static let website = URL(string: "https://slime.hyprnode.de")!

    private static var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.1"
    }

    private static var build: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"
    }

    var body: some View {
        List {
            Section {
                AboutHeader()
                    .frame(maxWidth: .infinity)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
            }

            Section {
                LabeledContent("Version", value: Self.version)
                    .contextMenu {
                        Button {
                            UIPasteboard.general.string = "Slimy \(Self.version) (\(Self.build))"
                        } label: {
                            Label("Copy Version", systemImage: "doc.on.doc")
                        }
                    }
                LabeledContent("Build", value: Self.build)
                LabeledContent("Made by", value: "hyprnode")
                Button {
                    showWhatsNew = true
                } label: {
                    Label("What's New in Slimy \(WhatsNew.version)", systemImage: "sparkles")
                }
                .sheet(isPresented: $showWhatsNew, onDismiss: WhatsNew.markSeen) {
                    WhatsNewView()
                        .environmentObject(store)
                }
            }

            Section {
                SettingsLinkRow(title: "Website", systemImage: "globe", url: Self.website)
                SettingsLinkRow(title: "Support", systemImage: "questionmark.bubble", url: FriendsStore.supportURL)
                SettingsLinkRow(title: "Privacy Policy", systemImage: "hand.raised", url: FriendsStore.privacyURL)
                SettingsLinkRow(title: "Terms of Use", systemImage: "doc.text", url: FriendsStore.termsURL)
                ShareLink(item: Self.website,
                          message: Text("Slimy: a tiny slime that lives in your Dynamic Island.")) {
                    Label("Share Slimy", systemImage: "square.and.arrow.up")
                }
            } header: {
                Text("Links")
            }

            Section {
                NavigationLink {
                    AboutLicenseView()
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Pixelify Sans")
                        Text("SIL Open Font License 1.1")
                            .font(.footnote)
                            .foregroundStyle(Theme.inkSoft)
                    }
                }
            } header: {
                Text("Open-source licenses")
            }

            Section {
                AboutCredit(title: "Pixelify Sans", detail: "By the Pixelify Sans Project Authors. The slime's voice and the wordmark.")
                AboutCredit(title: "AI replies", detail: "Talking with your slime uses Cloudflare Workers AI and Groq.")
                AboutCredit(title: "Art and sound", detail: "The pixel art, animations, sounds and music were made for Slimy.")
            } header: {
                Text("Acknowledgements")
            } footer: {
                Text("© 2026 hyprnode")
            }
        }
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// The app icon, the wordmark and the tagline, at the top of About.
private struct AboutHeader: View {
    var body: some View {
        VStack(spacing: 12) {
            AboutAppIcon(size: 96)
            // Pixelify Sans from its CTFont, and no rounded design: under the
            // app's .fontDesign(.rounded) a Font.custom turns into SF Rounded.
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("slimy")
                    .font(Brand.voice(34, relativeTo: .largeTitle)).fontDesign(nil)
                    .foregroundStyle(Theme.ink)
                Text("by hyprnode")
                    .font(Brand.voice(18, relativeTo: .headline)).fontDesign(nil)
                    .foregroundStyle(Brand.hypr)
            }
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Slimy by hyprnode")
            .accessibilityAddTraits(.isHeader)
            Text("A tiny slime that lives in your Dynamic Island.")
                .font(.subheadline)
                .foregroundStyle(Theme.inkSoft)
                .multilineTextAlignment(.center)
        }
        .padding(.vertical, 8)
    }
}

/// The app icon drawn from the sprites: the young lime slime on its dark
/// floor, laid out like `tools/make_icon.py` lays out the 1024 px icon.
private struct AboutAppIcon: View {
    let size: CGFloat

    var body: some View {
        let unit = size / 1024
        let frames = SlimeSprites.frames(.young, .idle)
        let anchors = SlimeSprites.anchors(.young, .idle)
        let shape = RoundedRectangle(cornerRadius: size * 0.2237, style: .continuous)
        ZStack(alignment: .topLeading) {
            Brand.bg
            Rectangle()
                .fill(Brand.line)
                .frame(width: size, height: 24 * unit)
                .offset(y: 874 * unit)
            PixelSlime(frame: frames.first ?? SlimeSprites.blank, skin: .lime, stage: .young,
                       anchor: anchors.first)
                .frame(width: 864 * unit, height: 720 * unit)
                .offset(x: 80 * unit, y: 154 * unit)
        }
        .frame(width: size, height: size)
        .clipShape(shape)
        .overlay(shape.strokeBorder(Theme.separator, lineWidth: 0.5))
        .accessibilityHidden(true)
    }
}

private struct AboutCredit: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.body)
                .foregroundStyle(Theme.ink)
            Text(detail)
                .font(.subheadline)
                .foregroundStyle(Theme.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

/// The full license of Pixelify Sans, which it asks to ship with the font.
private struct AboutLicenseView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ForEach(Array(AboutLicense.pixelify.enumerated()), id: \.offset) { _, block in
                    switch block {
                    case .heading(let text):
                        Text(text)
                            .font(.headline)
                            .foregroundStyle(Theme.ink)
                            .padding(.top, 8)
                            .accessibilityAddTraits(.isHeader)
                    case .text(let text):
                        Text(text)
                            .font(.footnote)
                            .foregroundStyle(Theme.ink)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .textSelection(.enabled)
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Theme.backdrop)
        .navigationTitle("Pixelify Sans")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private enum AboutLicense {
    enum Block {
        case heading(String)
        case text(String)
    }

    static let pixelify: [Block] = [
        .text("Copyright 2021 The Pixelify Sans Project Authors (https://github.com/eifetx/Pixelify-Sans)"),
        .text("This Font Software is licensed under the SIL Open Font License, Version 1.1. This license is copied below, and is also available with a FAQ at: https://scripts.sil.org/OFL"),
        .heading("SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007"),
        .heading("PREAMBLE"),
        .text("The goals of the Open Font License (OFL) are to stimulate worldwide development of collaborative font projects, to support the font creation efforts of academic and linguistic communities, and to provide a free and open framework in which fonts may be shared and improved in partnership with others."),
        .text("The OFL allows the licensed fonts to be used, studied, modified and redistributed freely as long as they are not sold by themselves. The fonts, including any derivative works, can be bundled, embedded, redistributed and/or sold with any software provided that any reserved names are not used by derivative works. The fonts and derivatives, however, cannot be released under any other type of license. The requirement for fonts to remain under this license does not apply to any document created using the fonts or their derivatives."),
        .heading("DEFINITIONS"),
        .text("\"Font Software\" refers to the set of files released by the Copyright Holder(s) under this license and clearly marked as such. This may include source files, build scripts and documentation."),
        .text("\"Reserved Font Name\" refers to any names specified as such after the copyright statement(s)."),
        .text("\"Original Version\" refers to the collection of Font Software components as distributed by the Copyright Holder(s)."),
        .text("\"Modified Version\" refers to any derivative made by adding to, deleting, or substituting -- in part or in whole -- any of the components of the Original Version, by changing formats or by porting the Font Software to a new environment."),
        .text("\"Author\" refers to any designer, engineer, programmer, technical writer or other person who contributed to the Font Software."),
        .heading("PERMISSION & CONDITIONS"),
        .text("Permission is hereby granted, free of charge, to any person obtaining a copy of the Font Software, to use, study, copy, merge, embed, modify, redistribute, and sell modified and unmodified copies of the Font Software, subject to the following conditions:"),
        .text("1) Neither the Font Software nor any of its individual components, in Original or Modified Versions, may be sold by itself."),
        .text("2) Original or Modified Versions of the Font Software may be bundled, redistributed and/or sold with any software, provided that each copy contains the above copyright notice and this license. These can be included either as stand-alone text files, human-readable headers or in the appropriate machine-readable metadata fields within text or binary files as long as those fields can be easily viewed by the user."),
        .text("3) No Modified Version of the Font Software may use the Reserved Font Name(s) unless explicit written permission is granted by the corresponding Copyright Holder. This restriction only applies to the primary font name as presented to the users."),
        .text("4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font Software shall not be used to promote, endorse or advertise any Modified Version, except to acknowledge the contribution(s) of the Copyright Holder(s) and the Author(s) or with their explicit written permission."),
        .text("5) The Font Software, modified or unmodified, in part or in whole, must be distributed entirely under this license, and must not be distributed under any other license. The requirement for fonts to remain under this license does not apply to any document created using the Font Software."),
        .heading("TERMINATION"),
        .text("This license becomes null and void if any of the above conditions are not met."),
        .heading("DISCLAIMER"),
        .text("THE FONT SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM OTHER DEALINGS IN THE FONT SOFTWARE."),
    ]
}
