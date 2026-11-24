import type { PromoLocale } from "../theme";

export interface PromoCopy {
  locale: PromoLocale;
  intro: {
    painHook: {
      phoneLabel: string;
      blockedBadge: string;
      speakerLabel: string;
      question: string;
      questionSize: number;
      subline: string;
      sublineSize: number;
    };
    brandPrimary: string;
    brandSecondary: string;
    headline: string;
    headlineSize: number;
    subtitle: string;
    subtitleSize: number;
    pills: readonly [string, string, string];
  };
  anyPhone: {
    eyebrow: string;
    headline: string;
    headlineSize: number;
    brands: readonly { name: string; en: string; sub: string }[];
    otherTitle: string;
    otherSub: string;
    speakerTitle: string;
    speakerBadge: string;
    bottomCallout: string;
  };
  oneApp: {
    act1Eyebrow: string;
    act1Headline: string;
    act1HeadlineSize: number;
    act1Subtitle: string;
    act1SubtitleSize: number;
    apps: readonly {
      id: string;
      name: string;
      sub: string;
      track: string;
      color: string;
      iconBg: string;
      badgeGlyph: string;
      cardY: number;
    }[];
    statusCasting: string;
    statusSwitched: string;
    statusStandby: string;
    pillAutoFollow: (appName: string) => string;
    pillSwitchedTo: (appName: string) => string;
    act1Footnote: string;
    speakerCastingPrefix: string;
    act2Eyebrow: string;
    act2Headline: string;
    act2HeadlineSize: number;
    act2Subtitle: string;
    act2SubtitleSize: number;
    phoneHeaderLeft: string;
    phoneHeaderRight: string;
    shortVideos: readonly {
      bg: string;
      tag: string;
      title: string;
      likes: string;
      emoji: string;
    }[];
    phoneSpeakerChip: string;
    shortVideoPlaying: string;
    shareLabel: string;
    voiceBannerTitle: string;
    voiceBannerSub: string;
    phoneCopyTitleLines: readonly [string, string];
    phoneCopyBodyLines: readonly [string, string, string];
    dividerTitle: string;
    dividerSub: string;
    speakerHeaderLeft: string;
    speakerHeaderRight: string;
    speakerBottomLine: string;
  };
  multiRoom: {
    eyebrow: string;
    headlinePart1: string;
    headlinePart2: string;
    headlineSize: number;
    phase1Title: string;
    phase1Sub: string;
    countPrefix: string;
    countSuffix: string;
    tiers: readonly {
      name: string;
      ms: number;
      tag: string;
      color: string;
      accent: string;
      pulseSpeed: number;
      rect: readonly [number, number, number, number];
    }[];
    effectPrefix: string;
    barLabels: readonly [string, string, string, string];
    rooms: readonly { x: number; y: number; w: number; h: number; name: string; spkX: number; spkY: number; t: number }[];
    waveTitle: string;
    waveSubLatency: string;
    waveSubSync: string;
    waveRooms: readonly [string, string, string];
    waveLabelWidth: number;
    waveStatusLatency: (tierName: string, ms: number) => string;
    waveStatusSync: string;
  };
  lan: {
    eyebrow: string;
    headline: string;
    headlineSize: number;
    subtitle: string;
    subtitleSize: number;
    card1Badge: string;
    card1Hint: string;
    card1Rect: readonly [number, number, number, number];
    card2Badge: string;
    card2Hint: string;
    card2Rect: readonly [number, number, number, number];
    zeroCloudTitle: string;
    routerLabel: string;
    nodePhone: string;
    nodeLiving: string;
    nodeBedroom: string;
    footerTitle: string;
    footerSub: string;
  };
  uiShowcase: {
    eyebrow1: string;
    headline1: string;
    eyebrow2: string;
    headline2: string;
    headlineSize: number;
    badges: readonly [string, string, string];
  };
  search: {
    prompt: string;
    placeholder: string;
    button: string;
    officialBadge: string;
    repoDesc: string;
  };
  outro: {
    brandPrimary: string;
    brandSecondary: string;
    tagline: string;
    taglineSize: number;
    tags: readonly [string, string, string, string];
    searchButton: string;
  };
}
