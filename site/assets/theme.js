// Applies the saved appearance before the first paint, so a page never
// flashes the wrong theme. A classic script on purpose: it runs in <head>.
(function () {
  var root = document.documentElement;
  root.classList.add("js");
  var theme = null;
  try {
    theme = new URLSearchParams(location.search).get("theme") || localStorage.getItem("slimy.theme");
  } catch (e) {}
  if (theme !== "light" && theme !== "dark") return;
  root.setAttribute("data-theme", theme);
  var color = theme === "dark" ? "#000000" : "#F2F2F7";
  var metas = document.querySelectorAll('meta[name="theme-color"]');
  for (var i = 0; i < metas.length; i++) metas[i].setAttribute("content", color);
})();
