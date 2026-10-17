package com.xushi;

import static org.junit.jupiter.api.Assertions.*;

import com.xushi.common.Problem;
import com.xushi.knowledge.DocumentParser;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DocumentParserTest {

  @TempDir
  Path directory;

  final DocumentParser parser = new DocumentParser();

  @Test
  void textAndWordKeepLocationsAndBoundedChunks() throws Exception {
    var text = directory.resolve("meeting.md");
    Files.writeString(
      text,
      "# 例会要求\n\n" + "项目进度和准备材料。".repeat(180)
    );
    var chunks = parser.parse(text, "meeting.md");
    assertTrue(chunks.size() > 2);
    assertTrue(chunks.stream().allMatch(x -> x.content().length() <= 700));
    assertTrue(chunks.get(0).locator().contains("第1节"));
    var word = directory.resolve("meeting.docx");
    try (
      var doc = new XWPFDocument();
      var stream = Files.newOutputStream(word)
    ) {
      doc.createParagraph().createRun().setText("例会持续60分钟");
      doc.createTable(1, 1).getRow(0).getCell(0).setText("准备进度表");
      doc.write(stream);
    }
    var paragraphs = parser.parse(word, "meeting.docx");
    assertEquals(2, paragraphs.size());
    assertTrue(paragraphs.get(1).content().contains("进度表"));
  }

  @Test
  void pdfKeepsPageReferencesAndRejectsEmptyScans() throws Exception {
    var file = directory.resolve("meeting.pdf");
    try (var doc = new PDDocument()) {
      var page = new PDPage();
      doc.addPage(page);
      try (var content = new PDPageContentStream(doc, page)) {
        content.beginText();
        content.setFont(
          new PDType1Font(Standard14Fonts.FontName.HELVETICA),
          12
        );
        content.newLineAtOffset(50, 700);
        content.showText("Meeting lasts 60 minutes.");
        content.endText();
      }
      doc.save(file.toFile());
    }
    var chunks = parser.parse(file, "meeting.pdf");
    assertTrue(chunks.get(0).locator().contains("第1页"));
    assertTrue(chunks.get(0).content().contains("60 minutes"));
    try (var doc = new PDDocument()) {
      doc.addPage(new PDPage());
      doc.save(file.toFile());
    }
    assertThrows(Problem.class, () -> parser.parse(file, "meeting.pdf"));
  }

  @Test
  void malformedAndOversizeTextFailClearly() throws Exception {
    var file = directory.resolve("bad.txt");
    Files.write(file, new byte[] { (byte) 0xff, (byte) 0xfe });
    assertThrows(Problem.class, () -> parser.parse(file, "bad.txt"));
    assertThrows(Problem.class, () ->
      parser.split("正文", "字".repeat(300001), new ArrayList<>())
    );
  }
}
