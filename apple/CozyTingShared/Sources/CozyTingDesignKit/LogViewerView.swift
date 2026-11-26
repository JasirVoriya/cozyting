import CozyTingCoreKit
import SwiftUI

/// Cross-platform Style B Runtime Log Viewer (`design/UI_SPEC.md` §6, zero `#if os(...)`).
///
/// Supports:
/// - 4-segment filter (`All` / `Warn/Err` / `Core` / `Ops`)
/// - Keyword search + Structured Timeline vs Raw Text toggle
/// - Date dividers, 3pt level/source color bar cards, flow-wrapped `key=value` pills,
///   collapsible pretty-formatted JSON (`>= 48` chars), per-entry copy, jump-to-latest
/// - Export `.txt`, Copy visible logs, and Clear with confirmation
public struct CozyLogViewerView: View {
    public let logManager: CozyTingLogManager
    public let language: AppLanguage
    public let palette: DesignTokens.ThemePalette
    public let appVersion: String
    public let onExportedFile: ((URL) -> Void)?
    public let onClose: (() -> Void)?

    @State private var selectedFilter: LogFilter = .all
    @State private var searchText: String = ""
    @State private var isStructuredMode: Bool = true
    @State private var rawLines: [String] = []
    @State private var entries: [ParsedLogEntry] = []
    @State private var totalBytes: UInt64 = 0
    @State private var warnCount: Int = 0
    @State private var errCount: Int = 0
    @State private var expandedJSONIds: Set<Int> = []
    @State private var showClearConfirm: Bool = false
    @State private var toastMessage: String?

    public init(
        logManager: CozyTingLogManager,
        language: AppLanguage,
        palette: DesignTokens.ThemePalette,
        appVersion: String = "0.9.12",
        onExportedFile: ((URL) -> Void)? = nil,
        onClose: (() -> Void)? = nil
    ) {
        self.logManager = logManager
        self.language = language
        self.palette = palette
        self.appVersion = appVersion
        self.onExportedFile = onExportedFile
        self.onClose = onClose
    }

    private func t(_ key: L10nKey) -> String {
        CozyTingL10n.text(key, language: language)
    }

    private var filteredEntries: [ParsedLogEntry] {
        let q = searchText.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !q.isEmpty else { return entries }
        return entries.filter { $0.raw.lowercased().contains(q) }
    }

    public var body: some View {
        VStack(spacing: DesignTokens.Spacing.md) {
            // Header
            HStack(spacing: DesignTokens.Spacing.sm) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(t(.logViewerTitle))
                        .font(.system(size: DesignTokens.Typography.pageTitle, weight: .bold))
                        .foregroundStyle(palette.textPrimary)

                    HStack(spacing: 6) {
                        Text(CozyTingL10n.format(
                            .logViewerSummary,
                            language: language,
                            filteredEntries.count,
                            CozyTingLogManager.formatSize(totalBytes)
                        ))
                        .font(.system(size: DesignTokens.Typography.micro))
                        .foregroundStyle(palette.textSecondary)

                        if warnCount > 0 {
                            Button { selectedFilter = .warnError } label: {
                                Text(CozyTingL10n.format(.logViewerWarnBadge, language: language, warnCount))
                                    .font(.system(size: 10, weight: .semibold))
                                    .foregroundStyle(palette.statusWarning)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(palette.statusWarning.opacity(0.16), in: Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                        if errCount > 0 {
                            Button { selectedFilter = .warnError } label: {
                                Text(CozyTingL10n.format(.logViewerErrBadge, language: language, errCount))
                                    .font(.system(size: 10, weight: .semibold))
                                    .foregroundStyle(palette.statusError)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(palette.statusError.opacity(0.16), in: Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                Spacer()

                Button { reloadLogs() } label: {
                    CozyTingIconView(.refresh, size: 13, weight: .medium)
                        .foregroundStyle(palette.textSecondary)
                        .frame(width: 28, height: 28)
                        .background(palette.controlSurface, in: Circle())
                }
                .buttonStyle(.plain)

                if let onClose {
                    Button(action: onClose) {
                        CozyTingIconView(.close, size: 13, weight: .semibold)
                            .foregroundStyle(palette.textSecondary)
                            .frame(width: 28, height: 28)
                            .background(palette.controlSurface, in: Circle())
                    }
                    .buttonStyle(.plain)
                }
            }

            // 4-Segment Filter
            Picker("", selection: $selectedFilter) {
                Text(t(.logViewerFilterAll)).tag(LogFilter.all)
                Text(t(.logViewerFilterWarnErr)).tag(LogFilter.warnError)
                Text(t(.logViewerFilterCore)).tag(LogFilter.core)
                Text(t(.logViewerFilterOps)).tag(LogFilter.ops)
            }
            .pickerStyle(.segmented)
            .onChange(of: selectedFilter) { _, _ in reloadLogs() }

            // Search + Structured/Raw toggle
            HStack(spacing: DesignTokens.Spacing.sm) {
                HStack(spacing: 6) {
                    CozyTingIconView(.search, size: 12)
                        .foregroundStyle(palette.textTertiary)
                    TextField(t(.logViewerSearchHint), text: $searchText)
                        .textFieldStyle(.plain)
                        .font(.system(size: DesignTokens.Typography.caption))
                        .foregroundStyle(palette.textPrimary)
                    if !searchText.isEmpty {
                        Button { searchText = "" } label: {
                            CozyTingIconView(.close, size: 10)
                                .foregroundStyle(palette.textTertiary)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 9)
                .padding(.vertical, 6)
                .background(palette.controlSurface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))

                Picker("", selection: $isStructuredMode) {
                    Text(t(.logViewerModeStructured)).tag(true)
                    Text(t(.logViewerModeRaw)).tag(false)
                }
                .pickerStyle(.segmented)
                .frame(width: 150)
            }

            // Main Log Surface
            logBodyView
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(
                    RoundedRectangle(cornerRadius: DesignTokens.Radii.card, style: .continuous)
                        .fill(palette.rowIdleBg.opacity(0.9))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: DesignTokens.Radii.card, style: .continuous)
                        .strokeBorder(palette.rowIdleBorder, lineWidth: 1)
                )

            if let toastMessage {
                Text(toastMessage)
                    .font(.system(size: DesignTokens.Typography.micro, weight: .medium))
                    .foregroundStyle(palette.accentBright)
            }

            // Footer Actions
            HStack(spacing: DesignTokens.Spacing.sm) {
                Button { exportLogs() } label: {
                    Text(t(.logViewerExport))
                        .font(.system(size: DesignTokens.Typography.caption, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 7)
                        .background(palette.accent, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain)

                Button {
                    let joined = filteredEntries.map(\.raw).joined(separator: "\n")
                    PlatformAdapters.copyToPasteboard(joined)
                    flashToast(t(.logViewerCopied))
                } label: {
                    Text(t(.logViewerCopy))
                        .font(.system(size: DesignTokens.Typography.caption, weight: .medium))
                        .foregroundStyle(palette.textPrimary)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(palette.controlSurface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain)

                Spacer()

                Button { showClearConfirm = true } label: {
                    Text(t(.logViewerClear))
                        .font(.system(size: DesignTokens.Typography.caption, weight: .medium))
                        .foregroundStyle(palette.statusError)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(palette.statusError.opacity(0.14), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(DesignTokens.Spacing.lg)
        .background(palette.panelBg)
        .onAppear { reloadLogs() }
        .alert(t(.logViewerClearTitle), isPresented: $showClearConfirm) {
            Button(t(.commonCancel), role: .cancel) {}
            Button(t(.logViewerClearConfirm), role: .destructive) {
                logManager.clearAll()
                reloadLogs()
            }
        } message: {
            Text(t(.logViewerClearMsg))
        }
    }

    @ViewBuilder
    private var logBodyView: some View {
        let items = filteredEntries
        if items.isEmpty {
            VStack {
                Spacer()
                Text(entries.isEmpty ? t(.logViewerEmpty) : t(.logViewerEmptyFiltered))
                    .font(.system(size: DesignTokens.Typography.caption))
                    .foregroundStyle(palette.textTertiary)
                Spacer()
            }
        } else if !isStructuredMode {
            ScrollView {
                Text(items.map(\.raw).joined(separator: "\n"))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(palette.textPrimary)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(DesignTokens.Spacing.md)
            }
        } else {
            ScrollViewReader { proxy in
                ZStack(alignment: .bottomTrailing) {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 6) {
                            ForEach(Array(items.enumerated()), id: \.element.id) { idx, entry in
                                if let date = entry.date,
                                   idx == 0 || items[idx - 1].date != date {
                                    HStack {
                                        Spacer()
                                        Text(date)
                                            .font(.system(size: 10.5, weight: .semibold, design: .monospaced))
                                            .foregroundStyle(palette.textTertiary)
                                            .padding(.horizontal, 10)
                                            .padding(.vertical, 2)
                                            .background(palette.controlSurface, in: Capsule())
                                        Spacer()
                                    }
                                    .padding(.vertical, 4)
                                }

                                structuredEntryCard(entry)
                                    .id(entry.id)
                            }
                        }
                        .padding(DesignTokens.Spacing.md)
                    }

                    if let lastID = items.last?.id {
                        Button {
                            withAnimation(.easeOut(duration: 0.2)) {
                                proxy.scrollTo(lastID, anchor: .bottom)
                            }
                        } label: {
                            Text("↓ \(t(.logViewerJumpLatest))")
                                .font(.system(size: 10.5, weight: .semibold))
                                .foregroundStyle(Color.white)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(palette.accent.opacity(0.92), in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .padding(10)
                    }
                }
                .onAppear {
                    if let lastID = items.last?.id {
                        proxy.scrollTo(lastID, anchor: .bottom)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func structuredEntryCard(_ entry: ParsedLogEntry) -> some View {
        let barColor: Color = {
            if entry.level == "E" || entry.level == "A" { return palette.statusError }
            if entry.level == "W" { return palette.statusWarning }
            if entry.isCore { return palette.accentBright }
            return palette.statusSuccess
        }()

        HStack(alignment: .top, spacing: 0) {
            Rectangle()
                .fill(barColor)
                .frame(width: 3)

            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 6) {
                    if let time = entry.time {
                        Text(time)
                            .font(.system(size: 10.5, design: .monospaced))
                            .foregroundStyle(palette.textTertiary)
                    }
                    Text(String(entry.level))
                        .font(.system(size: 9.5, weight: .bold, design: .monospaced))
                        .foregroundStyle(barColor)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(barColor.opacity(0.16), in: RoundedRectangle(cornerRadius: 4))

                    if !entry.tag.isEmpty {
                        Text(entry.tag)
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(palette.textSecondary)
                    }

                    Spacer()

                    Button {
                        PlatformAdapters.copyToPasteboard(entry.raw)
                        flashToast(t(.logViewerCopied))
                    } label: {
                        Image(systemName: "doc.on.doc")
                            .font(.system(size: 10))
                            .foregroundStyle(palette.textTertiary)
                    }
                    .buttonStyle(.plain)
                }

                if !entry.eventTitle.isEmpty {
                    Text(entry.eventTitle)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(palette.textPrimary)
                        .textSelection(.enabled)
                }

                if !entry.kvPairs.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 4) {
                            ForEach(Array(entry.kvPairs.enumerated()), id: \.offset) { _, kv in
                                HStack(spacing: 2) {
                                    Text("\(kv.key)=")
                                        .foregroundStyle(palette.textTertiary)
                                    Text(kv.value)
                                        .foregroundStyle(palette.textPrimary)
                                }
                                .font(.system(size: 10.5, design: .monospaced))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(palette.controlSurface, in: RoundedRectangle(cornerRadius: 5))
                            }
                        }
                    }
                }

                if let rawJSON = entry.jsonRaw, let summary = entry.jsonSummary {
                    let expanded = expandedJSONIds.contains(entry.id)
                    Button {
                        if expanded {
                            expandedJSONIds.remove(entry.id)
                        } else {
                            expandedJSONIds.insert(entry.id)
                        }
                    } label: {
                        let sizeStr = CozyTingLogManager.formatSize(UInt64(summary.byteCount))
                        let label = CozyTingL10n.format(.logViewerJsonItems, language: language, summary.itemCount ?? 0, sizeStr)
                        HStack(spacing: 4) {
                            CozyTingIconView(expanded ? .chevronDown : .chevronRight, size: 9, weight: .semibold)
                            Text("JSON · \(label)")
                                .font(.system(size: 10.5, weight: .medium, design: .monospaced))
                        }
                        .foregroundStyle(palette.accentBright)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(palette.accentSoftBg, in: Capsule())
                    }
                    .buttonStyle(.plain)

                    if expanded {
                        Text(LogEntryParser.prettyFormatJSON(rawJSON))
                            .font(.system(size: 10.5, design: .monospaced))
                            .foregroundStyle(palette.textSecondary)
                            .textSelection(.enabled)
                            .padding(8)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(palette.controlSurface, in: RoundedRectangle(cornerRadius: 8))
                    }
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
        }
        .background(palette.panelBg.opacity(0.6), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(palette.rowIdleBorder, lineWidth: 1)
        )
    }

    private func reloadLogs() {
        totalBytes = logManager.totalSizeBytes()
        let lines = logManager.readFilteredLines(filter: selectedFilter)
        rawLines = lines
        entries = LogEntryParser.parseLines(lines, defaultIsOps: selectedFilter == .ops)

        let allLines = logManager.readMergedRuntimeLines()
        warnCount = allLines.filter { $0.contains(" W/") }.count
        errCount = allLines.filter { $0.contains(" E/") }.count
    }

    private func exportLogs() {
        let header = """
        CozyTing Diagnostic Log Export
        Version: \(appVersion)
        OS: \(ProcessInfo.processInfo.operatingSystemVersionString)
        Generated: \(ISO8601DateFormatter().string(from: Date()))
        """
        let tempDir = FileManager.default.temporaryDirectory
        if let url = try? logManager.writeExportFile(to: tempDir, header: header) {
            onExportedFile?(url)
            flashToast(url.lastPathComponent)
        }
    }

    private func flashToast(_ msg: String) {
        toastMessage = msg
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(2))
            if toastMessage == msg {
                toastMessage = nil
            }
        }
    }
}
