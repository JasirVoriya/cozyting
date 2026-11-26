import CozyTingCoreKit
import CozyTingDesignKit
import SwiftUI

/// Optional standalone desktop main window sharing the same Style B tokens and `CastCoordinator`.
public struct MainWindowView: View {
    @Bindable public var coordinator: CastCoordinator
    public let onOpenSettings: () -> Void

    @Environment(\.colorScheme) private var systemColorScheme
    @State private var themeMode: ThemeMode = .system

    public init(
        coordinator: CastCoordinator,
        onOpenSettings: @escaping () -> Void
    ) {
        self.coordinator = coordinator
        self.onOpenSettings = onOpenSettings
    }

    private var effectiveScheme: ColorScheme {
        PlatformAdapters.preferredColorScheme(for: themeMode) ?? systemColorScheme
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
                        Text(BrandStrings.appDisplayName(for: coordinator.preferences.appLanguage))
                            .font(.system(size: DesignTokens.Typography.pageTitle, weight: .bold))
                            .foregroundStyle(palette.textPrimary)
                    }

                    Spacer()

                    Picker("", selection: $themeMode) {
                        Text("System").tag(ThemeMode.system)
                        Text("Light").tag(ThemeMode.light)
                        Text("Dark").tag(ThemeMode.dark)
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 190)
                    .onChange(of: themeMode) { _, newValue in
                        coordinator.preferences.themeMode = newValue
                    }

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
        .preferredColorScheme(PlatformAdapters.preferredColorScheme(for: themeMode))
        .onAppear {
            themeMode = coordinator.preferences.themeMode
        }
    }
}
