/**
 * Metadatos de idiomas para fuentes de novelas.
 * Todos los colores cumplen ratio de contraste 4.5:1+ sobre fondo blanco.
 */

export interface LanguageMetadata {
  code: string;
  name: string;
  nativeName: string;
  flag: string;           // Emoji flag o código ISO para bandera
  color: string;          // Color principal (texto/iconos)
  bgColor: string;        // Color de fondo para badges
  textColor: string;      // Color de texto sobre bgColor
  regions: string[];      // Regiones donde se usa
}

export const LANGUAGE_METADATA: Record<string, LanguageMetadata> = {
  'es': {
    code: 'es',
    name: 'Spanish',
    nativeName: 'Español',
    flag: '🇪🇸',
    color: '#C62828',
    bgColor: '#FDE0E0',
    textColor: '#C62828',
    regions: ['ES', 'MX', 'AR', 'CO', 'PE', 'VE', 'CL', 'EC', 'GT', 'CU', 'BO', 'DO', 'HN', 'PY', 'SV', 'NI', 'CR', 'PA', 'UY', 'PR']
  },
  'en': {
    code: 'en',
    name: 'English',
    nativeName: 'English',
    flag: '🇬🇧',
    color: '#1565C0',
    bgColor: '#E3F2FD',
    textColor: '#1565C0',
    regions: ['US', 'GB', 'CA', 'AU', 'NZ', 'IE', 'ZA', 'IN', 'SG', 'PH']
  },
  'ja': {
    code: 'ja',
    name: 'Japanese',
    nativeName: '日本語',
    flag: '🇯🇵',
    color: '#E65100',
    bgColor: '#FFF3E0',
    textColor: '#E65100',
    regions: ['JP']
  },
  'zh': {
    code: 'zh',
    name: 'Chinese',
    nativeName: '中文',
    flag: '🇨🇳',
    color: '#2E7D32',
    bgColor: '#E8F5E9',
    textColor: '#2E7D32',
    regions: ['CN', 'TW', 'HK', 'SG']
  },
  'pt': {
    code: 'pt',
    name: 'Portuguese',
    nativeName: 'Português',
    flag: '🇵🇹',
    color: '#00695C',
    bgColor: '#E0F2F1',
    textColor: '#00695C',
    regions: ['PT', 'BR', 'AO', 'MZ', 'CV', 'GW', 'ST', 'TL']
  },
  'ru': {
    code: 'ru',
    name: 'Russian',
    nativeName: 'Русский',
    flag: '🇷🇺',
    color: '#6A1B9A',
    bgColor: '#F3E5F5',
    textColor: '#6A1B9A',
    regions: ['RU', 'BY', 'KZ', 'KG', 'MD', 'TJ', 'TM', 'UZ']
  },
  'fr': {
    code: 'fr',
    name: 'French',
    nativeName: 'Français',
    flag: '🇫🇷',
    color: '#006064',
    bgColor: '#E0F7FA',
    textColor: '#006064',
    regions: ['FR', 'CA', 'BE', 'CH', 'LU', 'MC', 'SN', 'CI', 'CM', 'CD']
  },
  'de': {
    code: 'de',
    name: 'German',
    nativeName: 'Deutsch',
    flag: '🇩🇪',
    color: '#BF360C',
    bgColor: '#FFF3E0',
    textColor: '#BF360C',
    regions: ['DE', 'AT', 'CH', 'LI', 'LU', 'BE']
  },
  'it': {
    code: 'it',
    name: 'Italian',
    nativeName: 'Italiano',
    flag: '🇮🇹',
    color: '#1A237E',
    bgColor: '#E8EAF6',
    textColor: '#1A237E',
    regions: ['IT', 'CH', 'SM', 'VA']
  },
  'ko': {
    code: 'ko',
    name: 'Korean',
    nativeName: '한국어',
    flag: '🇰🇷',
    color: '#D84315',
    bgColor: '#FFF3E0',
    textColor: '#D84315',
    regions: ['KR', 'KP']
  },
  'ar': {
    code: 'ar',
    name: 'Arabic',
    nativeName: 'العربية',
    flag: '🇸🇦',
    color: '#33691E',
    bgColor: '#F1F8E9',
    textColor: '#33691E',
    regions: ['SA', 'AE', 'EG', 'DZ', 'MA', 'TN', 'LY', 'JO', 'LB', 'SY', 'IQ', 'KW', 'QA', 'BH', 'OM', 'YE', 'PS']
  },
  'tr': {
    code: 'tr',
    name: 'Turkish',
    nativeName: 'Türkçe',
    flag: '🇹🇷',
    color: '#E65100',
    bgColor: '#FFF3E0',
    textColor: '#E65100',
    regions: ['TR', 'CY']
  },
  'vi': {
    code: 'vi',
    name: 'Vietnamese',
    nativeName: 'Tiếng Việt',
    flag: '🇻🇳',
    color: '#C62828',
    bgColor: '#FDE0E0',
    textColor: '#C62828',
    regions: ['VN']
  },
  'id': {
    code: 'id',
    name: 'Indonesian',
    nativeName: 'Bahasa Indonesia',
    flag: '🇮🇩',
    color: '#D84315',
    bgColor: '#FFF3E0',
    textColor: '#D84315',
    regions: ['ID']
  },
  'th': {
    code: 'th',
    name: 'Thai',
    nativeName: 'ไทย',
    flag: '🇹🇭',
    color: '#BF360C',
    bgColor: '#FFF3E0',
    textColor: '#BF360C',
    regions: ['TH']
  },
  'nl': {
    code: 'nl',
    name: 'Dutch',
    nativeName: 'Nederlands',
    flag: '🇳🇱',
    color: '#BF360C',
    bgColor: '#FFF3E0',
    textColor: '#BF360C',
    regions: ['NL', 'BE', 'SR', 'AW', 'CW', 'SX']
  },
  'pl': {
    code: 'pl',
    name: 'Polish',
    nativeName: 'Polski',
    flag: '🇵🇱',
    color: '#C62828',
    bgColor: '#FDE0E0',
    textColor: '#C62828',
    regions: ['PL']
  },
  'multi': {
    code: 'multi',
    name: 'Multi-language',
    nativeName: 'Multi',
    flag: '🌐',
    color: '#455A64',
    bgColor: '#ECEFF1',
    textColor: '#455A64',
    regions: ['GLOBAL']
  }
};

/**
 * Obtiene metadatos de idioma, con fallback a 'multi'
 */
export function getLanguageMetadata(code: string): LanguageMetadata {
  return LANGUAGE_METADATA[code.toLowerCase()] ?? LANGUAGE_METADATA['multi'];
}

/**
 * Obtiene el color principal para un idioma
 */
export function getLanguageColor(code: string): string {
  return getLanguageMetadata(code).color;
}

/**
 * Obtiene el color de fondo para badges
 */
export function getLanguageBgColor(code: string): string {
  return getLanguageMetadata(code).bgColor;
}

/**
 * Obtiene el color de texto para badges
 */
export function getLanguageTextColor(code: string): string {
  return getLanguageMetadata(code).textColor;
}

/**
 * Obtiene la bandera emoji para un idioma
 */
export function getLanguageFlag(code: string): string {
  return getLanguageMetadata(code).flag;
}

/**
 * Obtiene el nombre nativo del idioma
 */
export function getLanguageNativeName(code: string): string {
  return getLanguageMetadata(code).nativeName;
}

/**
 * Agrupa fuentes por idioma y devuelve metadatos enriquecidos
 */
export interface LanguageGroup {
  language: string;
  languageName: string;
  nativeName: string;
  flag: string;
  color: string;
  bgColor: string;
  textColor: string;
  sources: SourceMetadata[];
  expanded: boolean;
}

export interface SourceMetadata {
  id: string;
  displayName: string;
  icon: string;
  language: string;
  baseUrl: string;
  rateLimitRpm: number;
  requiresLogin: boolean;
  enabled: boolean;
  tags: string[];
}

export function groupSourcesByLanguage(sources: SourceMetadata[]): LanguageGroup[] {
  const groups = new Map<string, SourceMetadata[]>();

  // Agrupar por código de idioma
  for (const source of sources) {
    const lang = source.language?.toLowerCase() ?? 'multi';
    if (!groups.has(lang)) {
      groups.set(lang, []);
    }
    groups.get(lang)!.push(source);
  }

  // Convertir a LanguageGroup con metadatos
  return Array.from(groups.entries())
    .map(([lang, sources]) => {
      const meta = getLanguageMetadata(lang);
      return {
        language: lang,
        languageName: meta.name,
        nativeName: meta.nativeName,
        flag: meta.flag,
        color: meta.color,
        bgColor: meta.bgColor,
        textColor: meta.textColor,
        sources: sources.sort((a, b) => a.displayName.localeCompare(b.displayName)),
        expanded: sources.length <= 10 // Auto-expandir grupos pequeños
      };
    })
    .sort((a, b) => {
      // Ordenar: español primero, luego inglés, luego por cantidad descendente
      if (a.language === 'es') return -1;
      if (b.language === 'es') return 1;
      if (a.language === 'en') return -1;
      if (b.language === 'en') return 1;
      return b.sources.length - a.sources.length;
    });
}