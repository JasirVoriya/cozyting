import CozyTingCoreKit
import CozyTingDesignKit
import SwiftUI

/// Native macOS translucent frosted-glass main window with unified titlebar controls.
public struct MainWindowView: View {
    @Bindable public var coordinator: CastCoordinator
    public let onOpenSettings: () -> Void
    public let onAppearanceChanged: ((ThemeMode) -> Void)?

    public init(
        coordinator: CastCoordinator,
        onOpenSettings: @escaping () -> Void,
        onAppearanceChanged: ((ThemeMode) -> Void)? = nil
    ) {
        self.coordinator = coordinator
        self.onOpenSettings = onOpenSettings
        self.onAppearanceChanged = onAppearanceChanged
    }

    private var lang: AppLanguage { coordinator.appLanguage }

    private var themeSymbol: String {
        switch coordinator.themeMode {
        case .system: return "circle.lefthalf.filled"
        case .light: return "sun.max.fill"
        case .dark: return "moon.fill"
        }
    }

    private var themeModeLabel: String {
        switch coordinator.themeMode {
        case .system: return CozyTingL10n.text(.themeSystem, language: lang)
        case .light: return CozyTingL10n.text(.themeLight, language: lang)
        case .dark: return CozyTingL10n.text(.themeDark, language: lang)
        }
    }

    private func cycleThemeMode() {
        let next: ThemeMode = {
            switch coordinator.themeMode {
            case .system: return .light
            case .light: return .dark
            case .dark: return .system
            }
        }()
        coordinator.setThemeMode(next)
        onAppearanceChanged?(next)
    }

    public var body: some View {
        ZStack {
            VisualEffectBackground(
                material: .hudWindow,
                blendingMode: .behindWindow,
                state: .followsWindowActiveState
            )
            .ignoresSafeArea()

            VStack(spacing: 0) {
                // Unified 38pt macOS titlebar strip aligned horizontally with the traffic lights
                ZStack {
                    HStack(spacing: 6) {
                        CozyTingIconView(.cozyHouse, size: 15)
                            .foregroundStyle(DesignTokens.Brand.coral)
                        Text(BrandStrings.appDisplayName(for: lang))
                            .font(.system(size: 13.5, weight: .semibold))
                            .foregroundStyle(.primary)
                    }

                    HStack(spacing: 4) {
                        Spacer()

                        GlassCircleIconButton(size: 26, action: cycleThemeMode) {
                            Image(systemName: themeSymbol)
                                .font(.system(size: 12, weight: .medium))
                                .foregroundStyle(.secondary)
                        }
                        .help(CozyTingL10n.format(.themeCycleTooltip, language: lang, themeModeLabel))

                        GlassCircleIconButton(size: 26, action: onOpenSettings) {
                            ZStack(alignment: .topTrailing) {
                                Image(systemName: "gearshape")
                                    .font(.system(size: 12.5, weight: .medium))
                                    .foregroundStyle(.secondary)
                                if coordinator.hasUpdateBadge {
                                    Circle()
                                        .fill(DesignTokens.Brand.coral)
                                        .frame(width: 6, height: 6)
                                        .offset(x: 2, y: -2)
                                }
                            }
                        }
                        .help(CozyTingL10n.text(.settingsTitle, language: lang))
                    }
                    .padding(.trailing, 12)
                }
                .frame(height: 38)
                .padding(.top, 2)

                PopoverControlCardView(
                    coordinator: coordinator,
                    isEmbeddedInMainWindow: true,
                    onOpenMainWindow: {},
                    onOpenSettings: onOpenSettings
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .ignoresSafeArea(.container, edges: .top)
        }
        .frame(minWidth: 400, minHeight: 540)
        .preferredColorScheme(PlatformAdapters.preferredColorScheme(for: coordinator.themeMode))
        .onAppear {
            coordinator.sourceCatalog.refreshCatalog()
        }
    }
}
