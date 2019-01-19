package io.kroki.server.service;

import io.kroki.server.action.Response;
import io.kroki.server.decode.DecodeException;
import io.kroki.server.decode.DiagramSource;
import io.kroki.server.format.ContentType;
import io.kroki.server.format.FileFormat;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import net.sourceforge.plantuml.BlockUml;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.PSystemError;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.code.Base64Coder;
import net.sourceforge.plantuml.code.Transcoder;
import net.sourceforge.plantuml.code.TranscoderUtil;
import net.sourceforge.plantuml.core.Diagram;

import java.io.*;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Plantuml {

  private static final List<FileFormat> SUPPORTED_FORMATS = Arrays.asList(FileFormat.PNG, FileFormat.SVG, FileFormat.JPEG, FileFormat.BASE64);
  private static final String supportedFormatList = FileFormat.stringify(SUPPORTED_FORMATS);

  private static final Pattern INCLUDE_RX = Pattern.compile("^\\s*!include(?:url)?\\s+.*");

  public static Handler<RoutingContext> convertRoute() {
    return routingContext -> {
      HttpServerResponse response = routingContext.response();
      String sourceEncoded = routingContext.request().getParam("source_encoded");
      String outputFormat = routingContext.request().getParam("output_format");
      FileFormat fileFormat = FileFormat.get(outputFormat);
      if (fileFormat == null || !SUPPORTED_FORMATS.contains(fileFormat)) {
        Response.handleUnsupportedFormat(response, outputFormat, supportedFormatList);
        return;
      }
      String source;
      try {
        source = decode(sourceEncoded);
        source = sanitize(source);
      } catch (DecodeException | IOException e) {
        response
          .setStatusCode(400)
          .end(e.getMessage());
        return;
      }
      byte[] data = convert(source, fileFormat);
      response
        .putHeader("Content-Type", ContentType.get(fileFormat))
        .end(Buffer.buffer(data));
    };
  }

  static byte[] convert(String source, FileFormat format) {
    try {
      SourceStringReader reader = new SourceStringReader(source);
      if (format == FileFormat.BASE64) {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        reader.outputImage(baos, 0, new FileFormatOption(FileFormat.PNG.toPlantumlFileFormat()));
        baos.close();
        final String encodedBytes = "data:image/png;base64,"
          + Base64Coder.encodeLines(baos.toByteArray()).replaceAll("\\s", "");
        return encodedBytes.getBytes();
      }
      final BlockUml blockUml = reader.getBlocks().get(0);
      final Diagram diagram = blockUml.getDiagram();
      if (diagram instanceof PSystemError) {
        throw new RuntimeException("Bad request");
      }
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      diagram.exportDiagram(byteArrayOutputStream, 0, new FileFormatOption(format.toPlantumlFileFormat()));
      return byteArrayOutputStream.toByteArray();
    } catch (IOException e) {
      throw new RuntimeException("Bad request", e);
    }
  }

  static String decode(String source) throws DecodeException, UnsupportedEncodingException {
    String text = URLDecoder.decode(source, "UTF-8");
    try {
      Transcoder transcoder = TranscoderUtil.getDefaultTranscoder();
      text = transcoder.decode(text);
      // encapsulate the UML syntax if necessary
    } catch (IOException ioException) {
      // Unable to decode with the PlantUML decoder, try the default decoder
      text = DiagramSource.decode(text);
    }
    String uml;
    if (text.startsWith("@start")) {
      uml = text;
    } else {
      StringBuilder plantUmlSource = new StringBuilder();
      plantUmlSource.append("@startuml\n");
      plantUmlSource.append(text);
      if (!text.endsWith("\n")) {
        plantUmlSource.append("\n");
      }
      plantUmlSource.append("@enduml");
      uml = plantUmlSource.toString();
    }
    return uml;
  }

  private static String sanitize(String input) throws IOException {
    try (BufferedReader reader = new BufferedReader(new StringReader(input))) {
      StringBuilder sb = new StringBuilder();
      String line = reader.readLine();
      while (line != null) {
        ignoreInclude(line, sb);
        line = reader.readLine();
      }
      return sb.toString();
    }
  }

  private static void ignoreInclude(String line, StringBuilder sb) {
    Matcher matcher = INCLUDE_RX.matcher(line);
    if (!matcher.matches()) {
      sb.append(line).append("\n");
    }
  }
}