import CozyTingCoreKit
import CozyTingDesignKit
import SwiftUI

/// Optional standalone desktop main window sharing the same Style B tokens and `CastCoordinator`.
public struct MainWindowView: View {
    @Bindable public var coordinator: CastCoordinator
    public let onOpenSettings: () -> Void
    public let onAppearanceChanged: ((ThemeMode) -> Void)?

    @Environment(\.colorScheme) private var systemColorScheme

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

    private var effectiveScheme: ColorScheme {
        PlatformAdapters.preferredColorScheme(for: coordinator.themeMode) ?? systemColorScheme
    }

    private var palette: DesignTokens.ThemePalette {
        DesignTokens.ThemePalette.palette(for: effectiveScheme)
    }

    public var body: some View {
        ZStack {
            LinearGradient(
                colors: [palette.bgAmbientTop, palette.bgAmbientBottom],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            VStack(spacing: DesignTokens.Spacing.lg) {
                HStack {
                    HStack(spacing: DesignTokens.Spacing.sm) {
                        CozyTingIconView(.cozyHouse, size: 22)
                            .foregroundStyle(DesignTokens.Brand.coral)
                        Text(BrandStrings.appDisplayName(for: lang))
                            .font(.system(size: DesignTokens.Typography.pageTitle, weight: .bold))
                            .foregroundStyle(palette.textPrimary)
                    }

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
                    .frame(width: 210)

                    Button(action: onOpenSettings) {
                        CozyTingIconView(.settings, size: 15)
                            .foregroundStyle(palette.textSecondary)
                            .frame(width: 30, height: 30)
                            .background(palette.controlSurface, in: Circle())
                    }
                    .buttonStyle(.plain)
                }

                PopoverControlCardView(
                    coordinator: coordinator,
                    onOpenMainWindow: {},
                    onOpenSettings: onOpenSettings
                )
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: DesignTokens.Radii.panel, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: DesignTokens.Radii.panel, style: .continuous)
                        .strokeBorder(palette.panelBorder, lineWidth: 1)
                )

                Spacer(minLength: 0)
            }
            .padding(DesignTokens.Spacing.xl)
        }
        .frame(minWidth: 460, minHeight: 560)
        .preferredColorScheme(PlatformAdapters.preferredColorScheme(for: coordinator.themeMode))
    }
}
