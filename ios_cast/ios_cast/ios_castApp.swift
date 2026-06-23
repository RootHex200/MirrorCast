//
//  ios_castApp.swift
//  ios_cast
//
//  Created by Roothex200 on 6/23/26.
//

import AppKit
import SwiftUI

@main
struct ios_castApp: App {
    @NSApplicationDelegateAdaptor(CastAppDelegate.self) private var appDelegate

    var body: some Scene {
        // Empty scene: window is owned by CastAppDelegate so we can apply
        // aspect-lock, persistence, and Cmd+F fullscreen.
        Settings {
            EmptyView()
        }
    }
}
