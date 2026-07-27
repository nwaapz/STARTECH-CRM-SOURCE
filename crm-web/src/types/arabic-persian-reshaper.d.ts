declare module 'arabic-persian-reshaper' {
  const reshaper: {
    PersianShaper: { convertArabic: (text: string) => string }
    ArabicShaper: { convertArabic: (text: string) => string }
  }
  export default reshaper
}
