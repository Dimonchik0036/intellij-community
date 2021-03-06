/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.compiler.impl;

import com.intellij.application.options.CodeStyle;
import com.intellij.compiler.CompilerMessageImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompilerMessage;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.pom.Navigatable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author Eugene Zhuravlev
 */
public class MessagesContainer {

  private static final int JAVAC_TAB_SIZE = 8;
  private final Project myProject;
  private final Map<CompilerMessageCategory, Collection<CompilerMessage>> myMessages = new EnumMap<>(CompilerMessageCategory.class);
  private final int myTabSize;

  public MessagesContainer(Project project) {
    myProject = project;
    myTabSize = CodeStyle.getDefaultSettings().getTabSize(StdFileTypes.JAVA);
  }

  @NotNull
  public Collection<CompilerMessage> getMessages(@NotNull CompilerMessageCategory category) {
    final Collection<CompilerMessage> collection = myMessages.get(category);
    if (collection == null) {
      return Collections.emptyList();
    }
    return Collections.unmodifiableCollection(collection);
  }

  @Nullable
  public CompilerMessage addMessage(CompilerMessageCategory category, String message, String url, int lineNum, int columnNum, Navigatable navigatable) {
    CompilerMessageImpl msg = new CompilerMessageImpl(myProject, category, message, findFileByUrl(url), lineNum, columnNum, navigatable);
    if (addMessage(msg)) {
      msg.setColumnAdjuster((m, line, col) -> adjustColumn(m, line, col));
      return msg;
    }
    return null;
  }

  private int adjustColumn(final CompilerMessage m, final int line, final int col) {
    if (myTabSize != JAVAC_TAB_SIZE && line >= 1) {
      // javac uses hard-coded tab size 8 chars. So recalculate only if project's codestyle is different
      final VirtualFile file = m.getVirtualFile();
      if (file != null && file.isValid()) {
        final int tabCount = ApplicationManager.getApplication().runReadAction((Computable<Integer>)() -> {
          final Document doc = FileDocumentManager.getInstance().getDocument(file);
          if (doc == null) {
            return 0;
          }
          int tcount = 0;
          final CharSequence seq = doc.getCharsSequence();
          final int start = doc.getLineStartOffset(line);
          final int end = doc.getLineEndOffset(line);
          int charsExpanded = 0;
          for (int i = start; i< end; i++) {
            if (seq.charAt(i) == '\t') {
              charsExpanded += JAVAC_TAB_SIZE;
              tcount++;
            }
            else {
              charsExpanded += 1;
            }
            if (charsExpanded >= col) {
              break; // consider only those tabs that are located before the given column number
            }
          }
          return tcount;
        });
        if (tabCount > 0) {
          return Math.max(0, col + tabCount * (myTabSize - JAVAC_TAB_SIZE));
        }
      }
    }
    return col;
  }

  public boolean addMessage(CompilerMessage msg) {
    Collection<CompilerMessage> messages = myMessages.computeIfAbsent(msg.getCategory(), k -> new LinkedHashSet<>());
    return messages.add(msg);
  }

  @Nullable
  private static VirtualFile findFileByUrl(@Nullable String url) {
    if (url == null) {
      return null;
    }
    VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(url);
    if (file == null) {
      // groovy stubs may be placed in completely random directories which aren't refreshed automatically
      return VirtualFileManager.getInstance().refreshAndFindFileByUrl(url);
    }
    return file;
  }

  public int getMessageCount(@Nullable CompilerMessageCategory category) {
    if (category != null) {
      Collection<CompilerMessage> collection = myMessages.get(category);
      return collection != null ? collection.size() : 0;
    }
    return myMessages.values().stream().filter(Objects::nonNull).mapToInt(Collection::size).sum();
  }

}
