import type { PromoLocale } from "../theme";
import type { PromoCopy } from "./types";
import { COPY_ZH } from "./zh-CN";
import { COPY_EN } from "./en";
import { COPY_JA } from "./ja";

export type { PromoCopy };

const DICTS: Record<PromoLocale, PromoCopy> = {
  zh: COPY_ZH,
  en: COPY_EN,
  ja: COPY_JA,
};

export function getCopy(locale: PromoLocale): PromoCopy {
  return DICTS[locale] ?? COPY_ZH;
}
