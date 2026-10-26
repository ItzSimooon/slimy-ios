// Light / dark / system appearance, remembered in this browser only.
// /admin/theme.js applies the saved choice before the first paint.

const KEY = "slimy.admin.theme";
const BG = { light: "#F2F2F7", dark: "#000000" };

export function getTheme() {
  try {
    const value = localStorage.getItem(KEY);
    return value === "light" || value === "dark" ? value : "system";
  } catch {
    return "system";
  }
}

export function setTheme(theme) {
  try {
    if (theme === "light" || theme === "dark") localStorage.setItem(KEY, theme);
    else localStorage.removeItem(KEY);
  } catch {
    // Storage blocked: the choice lasts until the page reloads.
  }
  applyTheme(theme);
}

export function applyTheme(theme = getTheme()) {
  const root = document.documentElement;
  if (theme === "light" || theme === "dark") root.dataset.theme = theme;
  else delete root.dataset.theme;
  for (const meta of document.querySelectorAll('meta[name="theme-color"]')) {
    const media = meta.getAttribute("media") ?? "";
    const own = media.includes("dark") ? BG.dark : BG.light;
    meta.setAttribute("content", theme === "light" || theme === "dark" ? BG[theme] : own);
  }
}
