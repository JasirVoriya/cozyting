import AppKit
import CozyTingCoreKit
import CozyTingDesignKit
import SwiftUI

/// Style B Favorite Audio Sources manager (`M6`).
///
/// Opening or managing favorites NEVER stops an active cast session. Selecting a new app
/// while casting performs a fail-closed Process Tap hot-switch without disconnecting speakers.
public struct AudioSourcePickerView: View {
    @Bindable public var coordinator: CastCoordinator
    public let palette: DesignTokens.ThemePalette
    public let onClose: () -> Void

    @State private var searchText: String = ""
    @State private var showAllOtherApps: Bool = false
    @State private var draggedBundleID: String?

    public init(
        coordinator: CastCoordinator,
        palette: DesignTokens.ThemePalette,
        onClose: @escaping () -> Void
    ) {
        self.coordinator = coordinator
        self.palette = palette
        self.onClose = onClose
    }

    private var favoriteBundleSet: Set<String> {
        Set(coordinator.favoriteSources.map { $0.bundleIdentifier.lowercased() })
    }

    private var filteredFavorites: [FavoriteAudioSource] {
        let q = searchText.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !q.isEmpty else { return coordinator.favoriteSources }
        return coordinator.favoriteSources.filter { fav in
            let name = coordinator.sourceCatalog.displayName(for: fav.bundleIdentifier).lowercased()
            return name.contains(q) || fav.bundleIdentifier.lowercased().contains(q)
        }
    }

    private var filteredOtherApps: [InstalledAppItem] {
        let q = searchText.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return coordinator.sourceCatalog.installedApps.filter { app in
            guard !favoriteBundleSet.contains(app.bundleIdentifier.lowercased()) else { return false }
            guard !q.isEmpty else { return true }
            return app.displayName.lowercased().contains(q)
                || app.bundleIdentifier.lowercased().contains(q)
        }
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
            // Header
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Favorite Audio Sources")
                        .font(.system(size: DesignTokens.Typography.cardTitle, weight: .bold))
                        .foregroundStyle(palette.textPrimary)
                    let autoCount = coordinator.favoriteSources.filter(\.autoDetectEnabled).count
                    Text("\(coordinator.favoriteSources.count) favorites (\(autoCount) auto-detect) · Click row to select")
                        .font(.system(size: DesignTokens.Typography.micro))
                        .foregroundStyle(palette.textSecondary)
                }

                Spacer()

                Button {
                    coordinator.sourceCatalog.refreshCatalog()
                } label: {
                    CozyTingIconView(.refresh, size: 13, weight: .medium)
                        .foregroundStyle(palette.textSecondary)
                        .frame(width: 26, height: 26)
                        .background(palette.controlSurface, in: Circle())
                }
                .buttonStyle(.plain)

                Button(action: onClose) {
                    CozyTingIconView(.close, size: 13, weight: .semibold)
                        .foregroundStyle(palette.textSecondary)
                        .frame(width: 26, height: 26)
                        .background(palette.controlSurface, in: Circle())
                }
                .buttonStyle(.plain)
            }

            // Search bar
            HStack(spacing: DesignTokens.Spacing.sm) {
                CozyTingIconView(.search, size: 13)
                    .foregroundStyle(palette.textTertiary)
                TextField("Search app name or bundle ID…", text: $searchText)
                    .textFieldStyle(.plain)
                    .font(.system(size: DesignTokens.Typography.body))
                    .foregroundStyle(palette.textPrimary)
                if !searchText.isEmpty {
                    Button { searchText = "" } label: {
                        CozyTingIconView(.close, size: 11)
                            .foregroundStyle(palette.textTertiary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(palette.controlSurface, in: RoundedRectangle(cornerRadius: DesignTokens.Radii.control, style: .continuous))

            ScrollView {
                VStack(alignment: .leading, spacing: DesignTokens.Spacing.md) {
                    // Favorites Section
                    HStack {
                        Text("Favorites (\(filteredFavorites.count))")
                            .font(.system(size: DesignTokens.Typography.sectionTitle, weight: .semibold))
                            .foregroundStyle(palette.textSecondary)
                        Spacer()
                        if filteredFavorites.count >= 2 && searchText.isEmpty {
                            Text("Drag to reorder")
                                .font(.system(size: DesignTokens.Typography.micro))
                                .foregroundStyle(palette.textTertiary)
                        }
                    }

                    if filteredFavorites.isEmpty {
                        Text("No favorite sources yet. Add apps below to enable quick-switch and auto-detect.")
                            .font(.system(size: DesignTokens.Typography.caption))
                            .foregroundStyle(palette.textTertiary)
                            .padding(.vertical, 6)
                    } else {
                        VStack(spacing: DesignTokens.Spacing.sm) {
                            ForEach(filteredFavorites, id: \.bundleIdentifier) { fav in
                                favoriteRow(fav)
                                    .draggable(fav.bundleIdentifier) {
                                        Text(coordinator.sourceCatalog.displayName(for: fav.bundleIdentifier))
                                            .padding(6)
                                            .background(palette.panelBg, in: RoundedRectangle(cornerRadius: 8))
                                    }
                                    .dropDestination(for: String.self) { items, _ in
                                        guard let fromID = items.first, fromID != fav.bundleIdentifier else {
                                            return false
                                        }
                                        coordinator.reorderFavoriteSource(from: fromID, to: fav.bundleIdentifier)
                                        return true
                                    }
                            }
                        }
                    }

                    // Other Installed Apps Section
                    let otherApps = filteredOtherApps
                    Button {
                        withAnimation(.easeInOut(duration: 0.16)) {
                            showAllOtherApps.toggle()
                        }
                    } label: {
                        HStack {
                            Text(
                                (showAllOtherApps || !searchText.isEmpty)
                                    ? "Other Apps (\(otherApps.count))"
                                    : "Add More Apps (\(otherApps.count))"
                            )
                            .font(.system(size: DesignTokens.Typography.sectionTitle, weight: .semibold))
                            .foregroundStyle(palette.accentBright)
                            Spacer()
                            CozyTingIconView(
                                (showAllOtherApps || !searchText.isEmpty) ? .chevronDown : .chevronRight,
                                size: 11,
                                weight: .semibold
                            )
                            .foregroundStyle(palette.accentBright)
                        }
                        .padding(.top, 4)
                    }
                    .buttonStyle(.plain)

                    if showAllOtherApps || !searchText.isEmpty {
                        LazyVStack(spacing: DesignTokens.Spacing.xs) {
                            ForEach(otherApps) { app in
                                otherAppRow(app)
                            }
                        }
                    }
                }
            }
            .frame(maxHeight: 380)
        }
        .padding(DesignTokens.Spacing.lg)
        .frame(width: 420)
        .background(palette.panelBg)
        .onAppear {
            coordinator.sourceCatalog.refreshCatalog()
        }
    }

    @ViewBuilder
    private func favoriteRow(_ fav: FavoriteAudioSource) -> some View {
        let name = coordinator.sourceCatalog.displayName(for: fav.bundleIdentifier)
        let isSelected = coordinator.activeSource?.bundleIdentifier.caseInsensitiveCompare(fav.bundleIdentifier) == .orderedSame
        let icon = coordinator.sourceCatalog.icon(for: fav.bundleIdentifier)

        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: DesignTokens.Spacing.sm) {
                appIconView(icon, size: 28)

                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 5) {
                        Text(name)
                            .font(.system(size: DesignTokens.Typography.deviceTitle, weight: .semibold))
                            .foregroundStyle(isSelected ? palette.onSelectedPrimary : palette.textPrimary)
                            .lineLimit(1)
                        if isSelected {
                            CozyTingIconView(.check, size: 11, weight: .bold)
                                .foregroundStyle(palette.accent)
                        }
                    }
                    Text(fav.bundleIdentifier)
                        .font(.system(size: 10.5, design: .monospaced))
                        .foregroundStyle(isSelected ? palette.onSelectedSecondary : palette.textTertiary)
                        .lineLimit(1)
                }

                Spacer(minLength: 4)

                Button {
                    coordinator.sourceCatalog.launchApplication(bundleIdentifier: fav.bundleIdentifier)
                } label: {
                    Image(systemName: "arrow.up.forward.app")
                        .font(.system(size: 12))
                        .foregroundStyle(isSelected ? palette.onSelectedSecondary : palette.textSecondary)
                }
                .buttonStyle(.plain)
                .help("Open \(name)")

                if filteredFavorites.count >= 2 && searchText.isEmpty {
                    CozyTingIconView(.dragHandle, size: 12)
                        .foregroundStyle(isSelected ? palette.onSelectedSecondary : palette.textTertiary)
                }
            }
            .contentShape(Rectangle())
            .onTapGesture {
                Task {
                    await coordinator.selectAudioSource(
                        AudioSourceSelection(bundleIdentifier: fav.bundleIdentifier, displayName: name),
                        manual: true
                    )
                }
            }

            HStack(spacing: DesignTokens.Spacing.sm) {
                Toggle(isOn: Binding(
                    get: { fav.autoDetectEnabled },
                    set: { enabled in
                        coordinator.setFavoriteAutoDetect(bundleIdentifier: fav.bundleIdentifier, enabled: enabled)
                    }
                )) {
                    Text(fav.autoDetectEnabled ? "Auto-detect: On" : "Auto-detect: Off")
                        .font(.system(size: DesignTokens.Typography.micro, weight: .medium))
                        .foregroundStyle(isSelected ? palette.onSelectedSecondary : palette.textSecondary)
                }
                .toggleStyle(.switch)
                .controlSize(.mini)

                Spacer()

                Button {
                    coordinator.removeFavoriteSource(bundleIdentifier: fav.bundleIdentifier)
                } label: {
                    Text("Remove")
                        .font(.system(size: DesignTokens.Typography.micro, weight: .medium))
                        .foregroundStyle(palette.statusError)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(DesignTokens.Spacing.md)
        .background(
            RoundedRectangle(cornerRadius: DesignTokens.Radii.control, style: .continuous)
                .fill(isSelected ? palette.rowSelectedBg : palette.rowIdleBg)
        )
        .overlay(
            RoundedRectangle(cornerRadius: DesignTokens.Radii.control, style: .continuous)
                .strokeBorder(isSelected ? palette.rowSelectedBorder : palette.rowIdleBorder, lineWidth: isSelected ? 1.5 : 1)
        )
    }

    @ViewBuilder
    private func otherAppRow(_ app: InstalledAppItem) -> some View {
        let icon = coordinator.sourceCatalog.icon(for: app.bundleIdentifier)
        let isSelected = coordinator.activeSource?.bundleIdentifier.caseInsensitiveCompare(app.bundleIdentifier) == .orderedSame

        HStack(spacing: DesignTokens.Spacing.sm) {
            appIconView(icon, size: 24)

            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 4) {
                    Text(app.displayName)
                        .font(.system(size: DesignTokens.Typography.caption, weight: .medium))
                        .foregroundStyle(palette.textPrimary)
                        .lineLimit(1)
                    if app.isRunning {
                        Circle()
                            .fill(palette.statusSuccess)
                            .frame(width: 5, height: 5)
                    }
                }
                Text(app.bundleIdentifier)
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(palette.textTertiary)
                    .lineLimit(1)
            }

            Spacer(minLength: 4)

            Button {
                coordinator.addFavoriteSource(bundleIdentifier: app.bundleIdentifier)
            } label: {
                Text("+ Fav")
                    .font(.system(size: DesignTokens.Typography.micro, weight: .semibold))
                    .foregroundStyle(palette.accentBright)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(palette.accentSoftBg, in: Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(
            RoundedRectangle(cornerRadius: DesignTokens.Radii.control, style: .continuous)
                .fill(isSelected ? palette.accentSoftBg : palette.rowIdleBg.opacity(0.6))
        )
        .contentShape(Rectangle())
        .onTapGesture {
            Task {
                await coordinator.selectAudioSource(
                    AudioSourceSelection(bundleIdentifier: app.bundleIdentifier, displayName: app.displayName),
                    manual: true
                )
            }
        }
    }

    @ViewBuilder
    private func appIconView(_ image: NSImage?, size: CGFloat) -> some View {
        if let image {
            Image(nsImage: image)
                .resizable()
                .interpolation(.high)
                .frame(width: size, height: size)
                .clipShape(RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
        } else {
            RoundedRectangle(cornerRadius: size * 0.22, style: .continuous)
                .fill(palette.controlSurface)
                .frame(width: size, height: size)
                .overlay(CozyTingIconView(.musicNote, size: size * 0.5).foregroundStyle(palette.textSecondary))
        }
    }
}
