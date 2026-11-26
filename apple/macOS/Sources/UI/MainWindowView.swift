import CozyTingCoreKit
import CozyTingDesignKit
import SwiftUI

/// Native macOS translucent frosted-glass main window.
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

    public var body: some View {
        ZStack {
            VisualEffectBackground(
                material: .hudWindow,
                blendingMode: .behindWindow,
                state: .followsWindowActiveState
            )
            .ignoresSafeArea()

            VStack(spacing: 0) {
                // Unified translucent titlebar row aligned alongside traffic lights
                HStack(spacing: 8) {
                    CozyTingIconView(.cozyHouse, size: 18)
                        .foregroundStyle(DesignTokens.Brand.coral)
                    Text(BrandStrings.appDisplayName(for: lang))
                        .font(.headline)
                        .foregroundStyle(.primary)

                    Spacer()

                    Picker("", selection: Binding(
                        get: { coordinator.themeMode },
                        set: { newMode in
                            coordinator.setThemeMode(newMode)
                            onAppearanceChanged?(newMode)
                        }
                    )) {
                        Text(CozyTingL10n.text(.themeSystem, language: lang)).tag(ThemeMode.system)
                        Text(CozyTingL10n.text(.themeLight, language: lang)).tag(ThemeMode.light)
                        Text(CozyTingL10n.text(.themeDark, language: lang)).tag(ThemeMode.dark)
                    }
                    .pickerStyle(.segmented)
                    .controlSize(.small)
                    .frame(width: 175)

                    Button(action: onOpenSettings) {
                        ZStack(alignment: .topTrailing) {
                            Image(systemName: "gearshape")
                                .font(.system(size: 13, weight: .medium))
                            if coordinator.hasUpdateBadge {
                                Circle()
                                    .fill(Color.accentColor)
                                    .frame(width: 7, height: 7)
                                    .offset(x: 3, y: -3)
                            }
                        }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
                .padding(.top, 34)
                .padding(.horizontal, 16)
                .padding(.bottom, 10)

                Divider()

                PopoverControlCardView(
                    coordinator: coordinator,
                    isEmbeddedInMainWindow: true,
                    onOpenMainWindow: {},
                    onOpenSettings: onOpenSettings
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(minWidth: 400, minHeight: 520)
        .preferredColorScheme(PlatformAdapters.preferredColorScheme(for: coordinator.themeMode))
        .onAppear {
            coordinator.sourceCatalog.refreshCatalog()
        }
    }
}
