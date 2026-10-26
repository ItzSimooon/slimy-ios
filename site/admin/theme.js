// Applies a saved light/dark choice before the first paint (no flash).
(function () {
  try {
    var theme = localStorage.getItem("slimy.admin.theme");
    if (theme === "light" || theme === "dark") document.documentElement.setAttribute("data-theme", theme);
  } catch (e) {
    // Storage blocked: follow the system.
  }
})();
