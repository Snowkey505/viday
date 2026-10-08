#!/usr/bin/env python3
# =============================================================================
# Статический HTML-снимок отчёта SonarQube (браузерный отчёт без сервера).
#
# SonarQube живёт только внутри CI-джобы (docker-compose.sonar.yml), поэтому
# "живой" UI сервера недоступен после пайплайна. Скрипт снимает через Web API
# итоговые метрики проекта и список открытых замечаний и формирует автономный
# sonar-report.html, который публикуется на gh-pages рядом с Allure и JaCoCo.
#
# Переменные окружения:
#   SONAR_HOST_URL  — адрес сервера (по умолчанию http://localhost:9002)
#   SONAR_TOKEN     — токен доступа (обязателен, если сервер требует auth)
#   SONAR_COMPONENT — ключ проекта (по умолчанию vidayapi)
#   SONAR_SNAPSHOT  — путь выходного файла (по умолчанию build/sonar/sonar-report.html)
# =============================================================================
import base64
import datetime
import html
import json
import os
import urllib.request

HOST = os.environ.get("SONAR_HOST_URL", "http://localhost:9002").rstrip("/")
COMPONENT = os.environ.get("SONAR_COMPONENT", "vidayapi")
TOKEN = os.environ.get("SONAR_TOKEN", "")
OUT = os.environ.get("SONAR_SNAPSHOT", os.path.join("build", "sonar", "sonar-report.html"))

RATINGS = {"1.0": "A", "2.0": "B", "3.0": "C", "4.0": "D", "5.0": "E"}


def api(path: str):
    req = urllib.request.Request(HOST + path)
    if TOKEN:
        req.add_header(
            "Authorization",
            "Basic " + base64.b64encode((TOKEN + ":").encode()).decode(),
        )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def fmt(value):
    if value is None:
        return "n/a"
    return RATINGS.get(value, value)


METRICS = (
    "alert_status,coverage,ncloc,lines,bugs,vulnerabilities,code_smells,"
    "security_hotspots,duplicated_lines_density,test_success_density,tests,"
    "reliability_rating,security_rating,sqale_rating"
)

LABELS = [
    ("alert_status", "Quality Gate"),
    ("coverage", "Покрытие, %"),
    ("ncloc", "Строки кода"),
    ("lines", "Всего строк"),
    ("bugs", "Баги"),
    ("vulnerabilities", "Уязвимости"),
    ("code_smells", "Code smells"),
    ("security_hotspots", "Security hotspots"),
    ("duplicated_lines_density", "Дублирование, %"),
    ("test_success_density", "Успех тестов, %"),
    ("tests", "Тестов"),
    ("reliability_rating", "Рейтинг надёжности"),
    ("security_rating", "Рейтинг безопасности"),
    ("sqale_rating", "Рейтинг сопровождаемости"),
]

page = [
    "<!DOCTYPE html><html lang='ru'><head><meta charset='utf-8'>",
    "<title>vidayapi — SonarQube report</title>",
    "<style>",
    "body{font-family:system-ui,sans-serif;margin:2rem;max-width:1100px}",
    "table{border-collapse:collapse;width:100%;margin:.5rem 0}",
    "td,th{border:1px solid #ccc;padding:6px 10px;text-align:left}",
    "th{background:#f0f0f0}.ok{color:#0a7d32;font-weight:600}.bad{color:#c00;font-weight:600}",
    "</style></head><body>",
    "<h1>SonarQube — vidayapi</h1>",
    "<p>Статический снимок отчёта SonarQube из CI · "
    f"{datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d %H:%M UTC')} · "
    f"проект: <code>{html.escape(COMPONENT)}</code> · покрытие: JaCoCo "
    "(unit + integration + e2e)</p>",
]

try:
    data = api(f"/api/measures/component?component={COMPONENT}&metricKeys={METRICS}")
    measures = {m["metric"]: m.get("value") for m in data["component"]["measures"]}
    page.append("<h2>Метрики проекта</h2>")
    page.append("<table><tr><th>Метрика</th><th>Значение</th></tr>")
    for key, label in LABELS:
        value = measures.get(key)
        css = ""
        if key == "alert_status":
            css = " class='ok'" if value == "OK" else " class='bad'"
        page.append(f"<tr><td>{label}</td><td{css}>{html.escape(fmt(value))}</td></tr>")
    page.append("</table>")

    issues = api(f"/api/issues/search?component={COMPONENT}&resolved=false&ps=100")
    rows = ""
    for issue in issues.get("issues", []):
        component = (issue.get("component") or "").split(":", 1)[-1]
        rows += (
            "<tr><td>{sev}</td><td>{typ}</td><td>{comp}</td><td>{line}</td><td>{msg}</td></tr>"
        ).format(
            sev=html.escape(issue.get("severity", "")),
            typ=html.escape(issue.get("type", "")),
            comp=html.escape(component),
            line=html.escape(str(issue.get("line", "-"))),
            msg=html.escape((issue.get("message") or "")[:200]),
        )
    total = issues.get("total", len(issues.get("issues", [])))
    page.append(f"<h2>Открытые замечания ({total})</h2>")
    if rows:
        page.append(
            "<table><tr><th>Серьёзность</th><th>Тип</th><th>Файл</th>"
            "<th>Строка</th><th>Сообщение</th></tr>" + rows + "</table>"
        )
    else:
        page.append("<p>Открытых замечаний нет.</p>")
except Exception as exc:  # noqa: BLE001 — снимок должен создаваться всегда
    page.append(
        f"<p><b>Не удалось получить данные SonarQube API:</b> {html.escape(str(exc))}. "
        "Сканер мог не опубликовать анализ — см. лог CI-джобы; "
        "report-task.txt приложен рядом.</p>"
    )

page.append("</body></html>")

os.makedirs(os.path.dirname(OUT) or ".", exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(page))
print(f"sonar-report.html written: {OUT}")
