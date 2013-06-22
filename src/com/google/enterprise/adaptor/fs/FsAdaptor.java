// Copyright 2013 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.adaptor.fs;

import com.google.common.base.Strings;
import com.google.enterprise.adaptor.AbstractAdaptor;
import com.google.enterprise.adaptor.AdaptorContext;
import com.google.enterprise.adaptor.Config;
import com.google.enterprise.adaptor.DocId;
import com.google.enterprise.adaptor.DocIdPusher;
import com.google.enterprise.adaptor.IOHelper;
import com.google.enterprise.adaptor.Request;
import com.google.enterprise.adaptor.Response;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

// TODO(mifern): Support\Verify that we can handle \\host\C$ shares.
// TODO(mifern): Support\Verify that we can handle \\host only shares.
// TODO(mifern): Decide what we want to discover within \\host only shares.

/**
 * Simple example adaptor that serves files from the local filesystem.
 */
public class FsAdaptor extends AbstractAdaptor {
  private static final Logger log
      = Logger.getLogger(FsAdaptor.class.getName());

  /** The config parameter name for the root path. */
  private static final String CONFIG_SRC = "filesystemadaptor.src";

  /** Charset used in generated HTML responses. */
  private static final Charset CHARSET = Charset.forName("UTF-8");

  private static final ThreadLocal<SimpleDateFormat> dateFormatter =
      new ThreadLocal<SimpleDateFormat>() {
          @Override
          protected SimpleDateFormat initialValue()
          {
              return new SimpleDateFormat("yyyy-MM-dd");
          }
      };

  private AdaptorContext context;

  private Path rootPath;

  public FsAdaptor() {
  }

  @Override
  public void initConfig(Config config) {
    // Setup default configuration values. The user is allowed to override them.
    config.addKey(CONFIG_SRC, null);
  }

  @Override
  public void init(AdaptorContext context) throws Exception {
    this.context = context;
    String source = context.getConfig().getValue(CONFIG_SRC);
    if (source.isEmpty()) {
      throw new IOException("The configuration value " + CONFIG_SRC
          + " is empty. Please specific a valid root path.");
    }
    rootPath = Paths.get(source);
    if (!isValidPath(rootPath)) {
      throw new IOException("The path " + rootPath + " is not a valid path.");
    }
    log.log(Level.CONFIG, "rootPath: {0}", rootPath);
  }

  // TODO(mifern): In Windows only change '\' to '/'.
  @Override
  public void getDocIds(DocIdPusher pusher) throws InterruptedException {
    log.entering("FsAdaptor", "getDocIds", new Object[] {pusher, rootPath});
    // TODO(mifern): rootPath was verified in the config but the directory
    // could have changed so we need to verify access again.
    pusher.pushDocIds(Arrays.asList(new DocId(rootPath.toString())));
    log.exiting("FsAdaptor", "getDocIds");
  }

  @Override
  public void getDocContent(Request req, Response resp) throws IOException {
    log.entering("FsAdaptor", "getDocContent",
        new Object[] {req, resp});
    DocId id = req.getDocId();
    // TODO(mifern): We need to normalize the doc path and confirm that the 
    // normalized path is the same of the requested path.
    String docPath = id.getUniqueId();

    Path doc = Paths.get(docPath);
    final String docName = getPathName(doc);

    if (!isFileDescendantOfRoot(doc)) {
      log.log(Level.WARNING,
          "Skipping {0} since it is not a descendant of {1}.",
          new Object[] { doc, rootPath });
      resp.respondNotFound();
      return;
    }

    if (!isValidPath(doc)) {
      // This is a non-supported file type.
      resp.respondNotFound();
      return;
    }

    // Populate the document metadata.
    BasicFileAttributes attrs = Files.readAttributes(doc,
        BasicFileAttributes.class);
    final FileTime lastAccessTime = attrs.lastAccessTime();

    resp.setLastModified(new Date(attrs.lastModifiedTime().toMillis()));
    resp.addMetadata("Creation Time", dateFormatter.get().format(
        new Date(attrs.creationTime().toMillis())));
    resp.addMetadata("Last Access Time",  dateFormatter.get().format(
        new Date(lastAccessTime.toMillis())));
    if (!Files.isDirectory(doc)) {
      resp.setContentType(Files.probeContentType(doc));
      resp.addMetadata("File Size", Long.toString(attrs.size()));
    }

    // TODO(mifern): Include extended attributes.

    // Populate the document ACL.

    // Populate the document content.
    if (Files.isRegularFile(doc)) {
      InputStream input = Files.newInputStream(doc);
      try {
        IOHelper.copyStream(input, resp.getOutputStream());
      } finally {
        try {
          input.close();
        } finally {
          try {
            Files.setAttribute(doc, "lastAccessTime", lastAccessTime);
          } catch (IOException e) {
            // This failure can be expected. We can have full permissions
            // to read but not write/update permissions.
            log.log(Level.CONFIG,
                "Unable to update last access time for {0}.", doc);
          }
        }
      }
    } else if (Files.isDirectory(doc)) {
      HtmlResponseWriter writer = createHtmlResponseWriter(resp);
      writer.start(id, getPathName(doc));
      for (Path file : Files.newDirectoryStream(doc)) {
        if (Files.isRegularFile(file) || Files.isDirectory(file)) {
          writer.addLink(new DocId(file.toString()), getPathName(file));
        }
      }
      writer.finish();
    }
    log.exiting("FsAdaptor", "getDocContent");
  }

  private HtmlResponseWriter createHtmlResponseWriter(Response response)
      throws IOException {
    Writer writer = new OutputStreamWriter(response.getOutputStream(),
        CHARSET);
    // TODO(ejona): Get locale from request.
    return new HtmlResponseWriter(writer, context.getDocIdEncoder(),
        Locale.ENGLISH);
  }
  
  private String getPathName(Path file) {
    return file.getName(file.getNameCount() - 1).toString();
  }

  private static boolean isValidPath(Path p) {
    return Files.isRegularFile(p) || !Files.isDirectory(p);
  }


 /*
  private String normalizeDocPath(String doc) {
    File docFile = new File(doc).getCanonicalFile();
  }
*/
  private boolean isFileDescendantOfRoot(Path file) {
    while (file != null) {
      if (file.equals(rootPath)) {
        return true;
      }
      file = file.getParent();
    }
    return false;
  }

  /** Call default main for adaptors. */
  public static void main(String[] args) {
    AbstractAdaptor.main(new FsAdaptor(), args);
  }
}
