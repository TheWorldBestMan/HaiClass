package cn.haike.schedule;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.IOException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PdfImport {
    private static final String WEEKDAYS = "一二三四五六日";
    private static final Pattern TERM = Pattern.compile("(20\\d{2})[-–](20\\d{2})学年第([12一二])学期");
    private static final Pattern PERIOD = Pattern.compile("^\\((\\d{1,2})(?:[-~至](\\d{1,2}))?节\\)");
    private static final Pattern WEEKS = Pattern.compile("(\\d{1,2})(?:[-~至](\\d{1,2}))?周?(?:\\(([单双])(?:周)?\\))?");
    private static final Pattern TITLE_MARK = Pattern.compile("[*$&]$");

    static Timetable parse(byte[] bytes) throws IOException {
        List<List<Glyph>> pages;
        try (PDDocument doc = PDDocument.load(bytes)) {
            if (doc.getNumberOfPages() > 30) throw new IOException("课表最多可导入 30 页");
            LayoutText reader = new LayoutText();
            reader.getText(doc);
            pages = reader.pages;
        }
        if (pages.isEmpty()) throw new IOException("这份 PDF 没有可读取的页面");
        List<Line> first = lines(pages.get(0));
        float[] weekdays = null;
        float header = 0;
        StringBuilder headerText = new StringBuilder();
        for (Line line : first) {
            headerText.append(normalize(line.text()));
            float[] centers = weekdayCenters(line);
            if (centers != null) {
                weekdays = centers;
                header = line.y;
                break;
            }
        }
        if (weekdays == null) throw new IOException("未找到星期表头。请选择教务系统打印的文字版个人课表 PDF。");

        Matcher termMatch = TERM.matcher(headerText);
        String term = "导入课表";
        LocalDate start = null;
        if (termMatch.find()) {
            boolean firstTerm = termMatch.group(3).matches("[1一]");
            term = termMatch.group(1) + "–" + termMatch.group(2) + " 学年 · 第" + (firstTerm ? "一" : "二") + "学期";
            if (termMatch.group(1).equals("2026") && termMatch.group(2).equals("2027")) {
                start = firstTerm ? Timetable.FALL : Timetable.SPRING;
            }
        }
        List<Timetable.Course> courses = new ArrayList<>();
        float spacing = (weekdays[6] - weekdays[0]) / 6;
        int expected = 0;
        for (int col = 0; col < 7; col++) {
            float left = col == 0 ? weekdays[0] - spacing / 2 : (weekdays[col - 1] + weekdays[col]) / 2;
            float right = col == 6 ? weekdays[6] + spacing / 2 : (weekdays[col] + weekdays[col + 1]) / 2;
            List<Line> column = new ArrayList<>();
            for (int page = 0; page < pages.size(); page++) {
                List<Glyph> cell = new ArrayList<>();
                for (Glyph glyph : pages.get(page)) {
                    if (glyph.x >= left && glyph.x < right && (page != 0 || glyph.y > header + 2)) cell.add(glyph);
                }
                column.addAll(lines(cell));
            }
            List<Integer> starts = new ArrayList<>();
            for (int i = 0; i < column.size(); i++) {
                if (PERIOD.matcher(normalize(column.get(i).text())).find()) starts.add(i);
            }
            expected += starts.size();
            for (int index = 0; index < starts.size(); index++) {
                int row = starts.get(index);
                if (row == 0) continue;
                int stop = index + 1 < starts.size() ? starts.get(index + 1) - 1 : column.size();
                StringBuilder data = new StringBuilder();
                for (int i = row; i < stop; i++) data.append(normalize(column.get(i).text()));

                Line marked = column.get(row - 1);
                String name = marked.text().trim();
                Matcher marker = TITLE_MARK.matcher(name);
                String type = "";
                if (marker.find()) {
                    type = switch (marker.group()) {
                        case "$" -> "实验";
                        case "&" -> "实践";
                        default -> "理论";
                    };
                    name = name.substring(0, marker.start()).trim();
                    for (int i = row - 2; i >= 0; i--) {
                        Line fragment = column.get(i);
                        if (Math.abs(fragment.fontSize() - marked.fontSize()) > 0.35f
                            || marked.y - fragment.y > 25 || marked.y <= fragment.y
                            || TITLE_MARK.matcher(fragment.text().trim()).find()) break;
                        name = fragment.text().trim() + name;
                        marked = fragment;
                    }
                }
                Matcher period = PERIOD.matcher(data);
                if (name.isEmpty() || !period.find()) continue;
                int begins = Integer.parseInt(period.group(1));
                int ends = period.group(2) == null ? begins : Integer.parseInt(period.group(2));
                int weekEnd = data.indexOf("/", period.end());
                if (weekEnd < 0 || begins < 1 || ends < begins || ends > Timetable.STANDARD.length) continue;
                long weeks = weeks(data.substring(period.end(), weekEnd));
                if (weeks == 0) continue;
                courses.add(new Timetable.Course(name, col + 1, begins, ends, weeks,
                    field(data, "校区"), field(data, "场地"), field(data, "教师"), type));
            }
        }
        if (courses.isEmpty()) throw new IOException("没有读到课程。请从教务系统重新打印文字版个人课表。");
        if (courses.size() != expected) throw new IOException("部分课程的节次或周次无法识别，请核对原课表格式。");
        return new Timetable(term, start, true, courses);
    }

    private static long weeks(String text) {
        long mask = 0;
        for (String part : text.split("[,、;]", -1)) {
            Matcher range = WEEKS.matcher(part.replace("第", ""));
            if (!range.matches()) return 0;
            int first = Integer.parseInt(range.group(1));
            int last = range.group(2) == null ? first : Integer.parseInt(range.group(2));
            if (first < 1 || last > 40 || last < first) return 0;
            String parity = range.group(3);
            for (int week = first; week <= last; week++) {
                if (parity == null || (week % 2 != 0 ? parity.equals("单") : parity.equals("双"))) mask |= 1L << week;
            }
        }
        return mask;
    }

    private static String field(CharSequence data, String key) {
        Matcher value = Pattern.compile("(?:^|/)" + key + ":?([^/]+)").matcher(data);
        return value.find() ? value.group(1) : "";
    }

    private static String normalize(String input) {
        return Normalizer.normalize(input, Normalizer.Form.NFKC).replaceAll("[\\s\\u00A0]", "")
            .replace('–', '-').replace('—', '-');
    }

    private static float[] weekdayCenters(Line line) {
        String text = line.text().replaceAll("\\s", "");
        float[] centers = new float[7];
        for (int i = 0; i < 7; i++) {
            String label = "星期" + WEEKDAYS.charAt(i);
            int index = text.indexOf(label);
            if (i == 6 && index < 0) index = text.indexOf("星期天");
            if (index < 0) return null;
            int offset = 0;
            for (Glyph glyph : line.glyphs) {
                int length = glyph.text.replaceAll("\\s", "").length();
                if (index + 1 >= offset && index + 1 < offset + length) {
                    centers[i] = glyph.x;
                    break;
                }
                offset += length;
            }
            if (i > 0 && centers[i] <= centers[i - 1]) return null;
        }
        return centers;
    }

    private static List<Line> lines(List<Glyph> glyphs) {
        List<Glyph> sorted = new ArrayList<>(glyphs);
        sorted.sort(Comparator.comparingDouble(g -> g.y));
        List<Line> rows = new ArrayList<>();
        Line row = null;
        for (Glyph glyph : sorted) {
            if (row == null || glyph.y - row.y > 3f) {
                row = new Line(glyph.y, new ArrayList<>());
                rows.add(row);
            }
            row.glyphs.add(glyph);
        }
        for (Line line : rows) line.glyphs.sort(Comparator.comparingDouble(g -> g.x));
        return rows;
    }

    private record Glyph(float x, float y, float size, String text) {}

    private record Line(float y, List<Glyph> glyphs) {
        String text() {
            StringBuilder text = new StringBuilder();
            for (Glyph glyph : glyphs) text.append(glyph.text);
            return text.toString();
        }
        float fontSize() { return glyphs.get(0).size; }
    }

    private static final class LayoutText extends PDFTextStripper {
        final List<List<Glyph>> pages = new ArrayList<>();
        List<Glyph> page;

        LayoutText() throws IOException {}

        @Override protected void startPage(PDPage source) {
            page = new ArrayList<>();
            pages.add(page);
        }

        @Override protected void processTextPosition(TextPosition position) {
            String value = position.getUnicode();
            if (value == null || value.isEmpty()) return;
            page.add(new Glyph(position.getXDirAdj() + position.getWidthDirAdj() / 2,
                position.getYDirAdj(), position.getFontSizeInPt(), value));
        }
    }
}
