// Stacked column chart as inline SVG, re-drawn at the container's real width
// so text stays crisp. Columns <= 24px with 4px rounded tops, a 2px surface
// gap between stacked segments, hairline grid, hover tooltip. The data table
// next to every chart is the accessible version; the SVG is one image.

import { h, s, clear } from "./dom.js";
import { fmtCompact, fmtInt } from "./format.js";

const PAD_TOP = 12;
const PAD_BOTTOM = 26;
const PAD_RIGHT = 4;
const GAP = 2;

function niceStep(max, ticks) {
  const raw = max / ticks;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const norm = raw / mag;
  const step = norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 2.5 ? 2.5 : norm <= 5 ? 5 : 10;
  return Math.max(1, step * mag);
}

function topRounded(x, y, w, hgt, r) {
  const rr = Math.min(r, w / 2, hgt);
  return `M${x},${y + hgt}V${y + rr}Q${x},${y} ${x + rr},${y}H${x + w - rr}Q${x + w},${y} ${x + w},${y + rr}V${y + hgt}Z`;
}

/**
 * @param {object} opts
 * @param {string[]} opts.labels   short x labels
 * @param {string[]} opts.titles   long labels for the tooltip
 * @param {{name: string, className: string, values: number[]}[]} opts.series  bottom to top
 * @param {string} opts.label      accessible name of the chart image
 * @param {number} [opts.height]
 */
export function columnChart({ labels, titles, series, label, height = 220, unit = "requests" }) {
  const svgHost = h("div", { class: "chart-svg" });
  const tooltip = h("div", { class: "chart-tooltip", "aria-hidden": "true", hidden: true });
  const el = h("div", { class: "chart", role: "img", "aria-label": label }, svgHost, tooltip);
  const n = labels.length;
  const totals = labels.map((_, i) => series.reduce((sum, ser) => sum + (ser.values[i] || 0), 0));
  const maxTotal = Math.max(0, ...totals);
  let width = 0;
  let hoverIndex = -1;
  let geometry = null;

  function render() {
    width = Math.floor(el.clientWidth);
    if (width <= 0) return;
    const ticks = 4;
    const step = niceStep(Math.max(maxTotal, ticks), ticks);
    const top = step * Math.ceil(Math.max(maxTotal, 1) / step);
    const tickValues = [];
    for (let v = 0; v <= top + step / 2; v += step) tickValues.push(v);
    const longest = Math.max(...tickValues.map((v) => fmtCompact(v).length));
    const padLeft = Math.max(22, longest * 7.5 + 10);
    const plotW = width - padLeft - PAD_RIGHT;
    const plotH = height - PAD_TOP - PAD_BOTTOM;
    const band = plotW / n;
    const barW = Math.max(2, Math.min(24, Math.floor(band * 0.64)));
    const y = (v) => PAD_TOP + plotH - (v / top) * plotH;

    const svg = s("svg", {
      width, height, viewBox: `0 0 ${width} ${height}`, class: "chart-plot", "aria-hidden": "true", focusable: "false",
    });

    const hover = s("rect", { class: "chart-hover", x: 0, y: PAD_TOP, width: band, height: plotH, rx: 6, visibility: "hidden" });
    svg.append(hover);

    for (const v of tickValues) {
      const yy = Math.round(y(v)) + 0.5;
      svg.append(s("line", { class: v === 0 ? "chart-baseline" : "chart-grid", x1: padLeft, x2: width - PAD_RIGHT, y1: yy, y2: yy }));
      svg.append(s("text", { class: "chart-tick", x: padLeft - 8, y: yy + 4, "text-anchor": "end" }, fmtCompact(v)));
    }

    // Label every k-th column, always including the newest one.
    const maxLabels = Math.max(2, Math.floor(plotW / 46));
    const every = Math.max(1, Math.ceil(n / maxLabels));
    for (let i = 0; i < n; i++) {
      const cx = padLeft + band * i + band / 2;
      if ((n - 1 - i) % every === 0) {
        svg.append(s("text", { class: "chart-xlabel", x: cx, y: height - 8, "text-anchor": "middle" }, labels[i]));
      }
      let base = y(0);
      const visible = series.map((ser) => ser.values[i] || 0);
      const lastIdx = visible.reduce((last, v, k) => (v > 0 ? k : last), -1);
      series.forEach((ser, k) => {
        const v = visible[k];
        if (v <= 0) return;
        const segH = (v / top) * plotH;
        const isTop = k === lastIdx;
        const drawH = isTop ? segH : Math.max(0, segH - GAP);
        const x = cx - barW / 2;
        const yTop = base - segH + (isTop ? 0 : GAP);
        if (drawH > 0.4) {
          svg.append(isTop
            ? s("path", { class: `chart-bar ${ser.className}`, d: topRounded(x, yTop, barW, drawH, 4) })
            : s("rect", { class: `chart-bar ${ser.className}`, x, y: yTop, width: barW, height: drawH }));
        }
        base -= segH;
      });
    }

    // One hit area over the whole plot; the column under the pointer is computed.
    svg.append(s("rect", { class: "chart-hit", x: padLeft, y: 0, width: plotW, height, fill: "transparent" }));
    geometry = { padLeft, band, plotW, y, top };
    clear(svgHost).append(svg);
    if (hoverIndex >= 0) showTip(hoverIndex);
  }

  function indexAt(clientX) {
    if (!geometry) return -1;
    const rect = svgHost.getBoundingClientRect();
    const x = clientX - rect.left - geometry.padLeft;
    if (x < 0 || x > geometry.plotW) return -1;
    return Math.min(n - 1, Math.max(0, Math.floor(x / geometry.band)));
  }

  function showTip(i) {
    if (!geometry || i < 0) return hideTip();
    hoverIndex = i;
    const hover = svgHost.querySelector(".chart-hover");
    if (hover) {
      hover.setAttribute("x", String(geometry.padLeft + geometry.band * i + 1));
      hover.setAttribute("width", String(Math.max(2, geometry.band - 2)));
      hover.setAttribute("visibility", "visible");
    }
    const rows = series.map((ser) => h("div", { class: "tip-row" },
      h("span", { class: `tip-swatch ${ser.className}` }),
      h("span", { class: "tip-name" }, ser.name),
      h("span", { class: "tip-value" }, fmtInt(ser.values[i] || 0))));
    clear(tooltip).append(
      h("div", { class: "tip-title" }, titles?.[i] ?? labels[i]),
      ...rows,
      series.length > 1 ? h("div", { class: "tip-row tip-total" },
        h("span", { class: "tip-name" }, "Total"), h("span", { class: "tip-value" }, `${fmtInt(totals[i])} ${unit}`)) : null);
    tooltip.hidden = false;
    const tipW = tooltip.offsetWidth;
    const tipH = tooltip.offsetHeight;
    const cx = geometry.padLeft + geometry.band * i + geometry.band / 2;
    const left = Math.min(Math.max(0, cx - tipW / 2), width - tipW);
    const barTop = geometry.y(totals[i]);
    const topPos = Math.max(0, barTop - tipH - 10);
    tooltip.style.transform = `translate(${Math.round(left)}px, ${Math.round(topPos)}px)`;
  }

  function hideTip() {
    hoverIndex = -1;
    tooltip.hidden = true;
    svgHost.querySelector(".chart-hover")?.setAttribute("visibility", "hidden");
  }

  svgHost.addEventListener("pointermove", (event) => showTip(indexAt(event.clientX)));
  svgHost.addEventListener("pointerdown", (event) => showTip(indexAt(event.clientX)));
  svgHost.addEventListener("pointerleave", (event) => {
    if (event.pointerType === "mouse") hideTip();
  });

  const observer = new ResizeObserver(() => {
    if (Math.floor(el.clientWidth) !== width) render();
  });
  observer.observe(el);
  requestAnimationFrame(render);

  return { el, dispose: () => observer.disconnect() };
}

/** Legend for two or more series: swatch + name, text in text colours. */
export function legend(series) {
  return h("ul", { class: "legend", "aria-hidden": "true" },
    ...series.map((ser) => h("li", null, h("span", { class: `legend-swatch ${ser.className}` }), ser.name)));
}

/** A plain data table with a caption, numbers right-aligned. */
export function dataTable({ caption, columns, rows, className = "" }) {
  return h("div", { class: `table-wrap ${className}`.trim(), tabindex: "0", role: "region", "aria-label": caption },
    h("table", { class: "data-table" },
      h("caption", { class: "sr-only" }, caption),
      h("thead", null, h("tr", null, ...columns.map((c) => h("th", { scope: "col", class: c.numeric ? "num" : null }, c.label)))),
      h("tbody", null, ...rows.map((r) => h("tr", null, ...r.map((cell, i) => (i === 0
        ? h("th", { scope: "row" }, cell)
        : h("td", { class: columns[i].numeric ? "num" : null }, cell))))))));
}
