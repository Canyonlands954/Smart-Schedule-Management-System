package com.xushi.knowledge;

import com.xushi.common.Problem;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

@Component
public class DocumentParser {

  public record Chunk(String locator, String content) {}

  private static final int MAX_TEXT = 300000,
    MAX_CHUNKS = 600;

  public List<Chunk> parse(Path file, String filename) {
    var result = new ArrayList<Chunk>();
    try {
      String ext = filename
        .substring(filename.lastIndexOf('.') + 1)
        .toLowerCase(Locale.ROOT);
      if (ext.equals("pdf")) {
        try (var pdf = Loader.loadPDF(file.toFile())) {
          if (
            pdf.isEncrypted() || pdf.getNumberOfPages() > 200
          ) throw new Problem("PDF需未加密且不超过200页");
          var stripper = new PDFTextStripper();
          stripper.setSortByPosition(true);
          for (int i = 1; i <= pdf.getNumberOfPages(); i++) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            split("第" + i + "页", stripper.getText(pdf), result);
            limit(result);
          }
        }
      } else if (ext.equals("docx")) {
        try (
          var input = Files.newInputStream(file);
          var doc = new XWPFDocument(input)
        ) {
          int i = 0;
          for (var element : doc.getBodyElements()) {
            i++;
            String text =
              element instanceof XWPFParagraph p
                ? p.getText()
                : element instanceof XWPFTable t
                  ? t.getText()
                  : "";
            split("正文第" + i + "段/表", text, result);
            limit(result);
          }
        }
      } else if (ext.equals("txt") || ext.equals("md")) {
        byte[] bytes = Files.readAllBytes(file);
        String text;
        try {
          text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString();
        } catch (CharacterCodingException e) {
          throw new Problem("文本文件请保存为UTF-8编码");
        }
        int section = 0;
        for (String block : text.split("\\n\\s*\\n")) {
          split("第" + ++section + "节", block, result);
          limit(result);
        }
      } else throw new Problem("支持PDF、DOCX、Markdown和TXT文件");
      limit(result);
      if (result.isEmpty()) throw new Problem(
        "没有提取到正文；扫描件请先进行文字识别"
      );
      return result;
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      throw new Problem("文档解析失败，请检查文件是否损坏、加密或格式不正确");
    }
  }

  public void split(String locator, String text, List<Chunk> out) {
    text = text.replace("\u0000", "").replace("\r", "").strip();
    if (text.isBlank()) return;
    if (text.length() > MAX_TEXT) throw new Problem("文档正文过长，请拆分文件");
    for (int start = 0; start < text.length(); ) {
      int end = Math.min(start + 700, text.length());
      if (end < text.length()) {
        int stop = text.lastIndexOf('。', end);
        if (stop > start + 400) end = stop + 1;
      }
      out.add(
        new Chunk(
          locator + " · 片段" + (out.size() + 1),
          text.substring(start, end)
        )
      );
      if (out.size() > MAX_CHUNKS) throw new Problem(
        "文档片段过多，请拆分文件"
      );
      if (end == text.length()) break;
      start = end - 80;
    }
  }

  private void limit(List<Chunk> chunks) {
    if (
      chunks.size() > MAX_CHUNKS ||
      chunks
        .stream()
        .mapToInt(x -> x.content().length())
        .sum() > MAX_TEXT
    ) throw new Problem(
      "文档正文过长，请拆分为多个文件（最多30万字符、600片段）"
    );
  }
}
