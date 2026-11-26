import AppKit
import CozyTingCoreKit
import CozyTingDesignKit
import SwiftUI

/// Native macOS frosted-glass Favorite Audio Sources manager (`M6`, trilingual `zh-CN` / `en` / `ja`).
public struct AudioSourcePickerView: View {
    @Bindable public var coordinator: CastCoordinator
    public let palette: DesignTokens.ThemePalette
    public let preferredWidth: CGFloat
    public let onClose: () -> Void

    @State private var searchText: String = ""
    @State private var showAllOtherApps: Bool = false

    public init(
        coordinator: CastCoordinator,
        palette: DesignTokens.ThemePalette,
        preferredWidth: CGFloat = 420,
        onClose: @escaping () -> Void
    ) {
        self.coordinator = coordinator
        self.palette = palette
        self.preferredWidth = preferredWidth
        self.onClose = onClose
    }

    private var lang: AppLanguage { coordinator.appLanguage }

    private func t(_ key: L10nKey) -> String {
        CozyTingL10n.text(key, language: lang)
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
        ZStack {
            VisualEffectBackground(
                material: .popover,
                blendingMode: .behindWindow,
                state: .active
            )
            .ignoresSafeArea()

            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t(.pickerTitle))
                            .font(.headline)
                            .foregroundStyle(.primary)
                        let autoCount = coordinator.favoriteSources.filter(\.autoDetectEnabled).count
                        Text(CozyTingL10n.format(.pickerSummary, language: lang, coordinator.favoriteSources.count, autoCount))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    Spacer()

                    Button {
                        coordinator.sourceCatalog.refreshCatalog()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 12, weight: .medium))
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)

                    Button(action: onClose) {
                        Image(systemName: "xmark")
                            .font(.system(size: 11, weight: .semibold))
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }

                // Native macOS search field
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    TextField(t(.pickerSearchHint), text: $searchText)
                        .textFieldStyle(.plain)
                        .font(.subheadline)
                    if !searchText.isEmpty {
                        Button { searchText = "" } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(.quaternary.opacity(0.7), in: RoundedRectangle(cornerRadius: 8, style: .continuous))

                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Text(CozyTingL10n.format(.pickerFavoritesHeader, language: lang, filteredFavorites.count))
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                            Spacer()
                            if filteredFavorites.count >= 2 && searchText.isEmpty {
                                Text(t(.pickerDragHint))
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                            }
                        }

                        if filteredFavorites.isEmpty {
                            Text(t(.pickerFavoritesEmpty))
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                                .padding(.vertical, 6)
                        } else {
                            VStack(spacing: 6) {
                                ForEach(filteredFavorites, id: \.bundleIdentifier) { fav in
                                    favoriteRow(fav)
                                        .draggable(fav.bundleIdentifier) {
                                            Text(coordinator.sourceCatalog.displayName(for: fav.bundleIdentifier))
                                                .padding(6)
                                                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 8))
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

                        let otherApps = filteredOtherApps
                        Button {
                            withAnimation(.easeInOut(duration: 0.16)) {
                                showAllOtherApps.toggle()
                            }
                        } label: {
                            HStack {
                                Text(
                                    (showAllOtherApps || !searchText.isEmpty)
                                        ? CozyTingL10n.format(.pickerOtherAppsHeader, language: lang, otherApps.count)
                                        : CozyTingL10n.format(.pickerAddMoreApps, language: lang, otherApps.count)
                                )
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(Color.accentColor)
                                Spacer()
                                Image(systemName: (showAllOtherApps || !searchText.isEmpty) ? "chevron.down" : "chevron.right")
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(Color.accentColor)
                            }
                            .padding(.top, 4)
                        }
                        .buttonStyle(.plain)

                        if showAllOtherApps || !searchText.isEmpty {
                            LazyVStack(spacing: 4) {
                                ForEach(otherApps) { app in
                                    otherAppRow(app)
                                }
                            }
                        }
                    }
                }
                .frame(maxHeight: 380)
            }
            .padding(14)
        }
        .frame(width: preferredWidth)
        .onAppear {
            coordinator.sourceCatalog.refreshCatalog()
        }
    }

    @ViewBuilder
    private func favoriteRow(_ fav: FavoriteAudioSource) -> some View {
        let name = coordinator.sourceCatalog.displayName(for: fav.bundleIdentifier)
        let isSelected = coordinator.activeSource?.bundleIdentifier.caseInsensitiveCompare(fav.bundleIdentifier) == .orderedSame
        let isRunning = coordinator.sourceCatalog.isRunning(bundleIdentifier: fav.bundleIdentifier)
        let icon = coordinator.sourceCatalog.icon(for: fav.bundleIdentifier)

        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                appIconView(icon, size: 28)

                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 5) {
                        Text(name)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        if isRunning {
                            Circle()
                                .fill(.green)
                                .frame(width: 6, height: 6)
                        }
                        if isSelected {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.caption)
                                .foregroundStyle(Color.accentColor)
                        }
                    }
                    Text(fav.bundleIdentifier)
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Spacer(minLength: 4)

                Button {
                    coordinator.sourceCatalog.launchApplication(bundleIdentifier: fav.bundleIdentifier)
                } label: {
                    Image(systemName: "arrow.up.forward.app")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .help("\(t(.commonOpen)) \(name)")

                if filteredFavorites.count >= 2 && searchText.isEmpty {
                    Image(systemName: "line.3.horizontal")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
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

            HStack {
                Toggle(isOn: Binding(
                    get: { fav.autoDetectEnabled },
                    set: { enabled in
                        coordinator.setFavoriteAutoDetect(bundleIdentifier: fav.bundleIdentifier, enabled: enabled)
                    }
                )) {
                    Text(t(fav.autoDetectEnabled ? .pickerAutoDetectOn : .pickerAutoDetectOff))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .toggleStyle(.switch)
                .controlSize(.mini)

                Spacer()

                Button(t(.pickerRemove)) {
                    coordinator.removeFavoriteSource(bundleIdentifier: fav.bundleIdentifier)
                }
                .buttonStyle(.plain)
                .font(.caption2)
                .foregroundStyle(.red)
            }
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(isSelected ? Color.accentColor.opacity(0.12) : Color.primary.opacity(0.05))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(isSelected ? Color.accentColor.opacity(0.45) : Color.primary.opacity(0.08), lineWidth: isSelected ? 1 : 0.5)
        )
    }

    @ViewBuilder
    private func otherAppRow(_ app: InstalledAppItem) -> some View {
        let icon = coordinator.sourceCatalog.icon(for: app.bundleIdentifier)
        let isSelected = coordinator.activeSource?.bundleIdentifier.caseInsensitiveCompare(app.bundleIdentifier) == .orderedSame

        HStack(spacing: 8) {
            appIconView(icon, size: 24)

            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 4) {
                    Text(app.displayName)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    if app.isRunning {
                        Circle()
                            .fill(.green)
                            .frame(width: 5, height: 5)
                    }
                }
                Text(app.bundleIdentifier)
                    .font(.caption2.monospaced())
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
            }

            Spacer(minLength: 4)

            Button(t(.pickerAddFav)) {
                coordinator.addFavoriteSource(bundleIdentifier: app.bundleIdentifier)
            }
            .buttonStyle(.bordered)
            .controlSize(.mini)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(
            RoundedRectangle(cornerRadius: 9, style: .continuous)
                .fill(isSelected ? Color.accentColor.opacity(0.12) : Color.primary.opacity(0.04))
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
                .fill(Color.primary.opacity(0.08))
                .frame(width: size, height: size)
                .overlay(Image(systemName: "music.note").font(.system(size: size * 0.45)).foregroundStyle(.secondary))
        }
    }
}
