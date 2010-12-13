/*
 * JBoss, by Red Hat.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.seam.forge.shell.plugins.builtin;

import org.jboss.seam.forge.project.Resource;
import org.jboss.seam.forge.shell.Shell;
import org.jboss.seam.forge.shell.plugins.*;
import org.jboss.seam.forge.shell.util.ShellColor;
import org.mvel2.util.StringAppender;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Implementation of more & less, but called more.
 *
 * @author Mike Brock .
 */
@Named("more")
@Topic("Shell Environment")
public class MorePlugin implements Plugin
{
   private static final String MOREPROMPT = "--[SPACE:PageDn U:PageUp ENT:LineDn J:LineUp Q:Quit]--";
   private static final String SEARCH_FORWARD_PROMPT = "Search-Foward: ";
   private static final String SEARCH_BACKWARDS_PROMPT = "Search-Backwards: ";
   private static final String PATTERN_NOT_FOUND = "-- Pattern not found: ";


   private final Shell shell;

   @Inject
   public MorePlugin(Shell shell)
   {
      this.shell = shell;
   }

   @DefaultCommand
   public void run(@PipeIn InputStream pipeIn,
                   final Resource<?> file,
                   final PipeOut pipeOut)
         throws IOException
   {
      if (file != null)
      {
         InputStream fileInstream = null;
         try
         {
            fileInstream = file.getResourceInputStream();
            more(fileInstream, pipeOut);
         }
         finally
         {
            if (fileInstream != null)
            {
               fileInstream.close();
            }
         }
      }
      else if (pipeIn != null)
      {
         more(pipeIn, pipeOut);
      }
   }

   private void more(InputStream stream, PipeOut out) throws IOException
   {
      byte[] buffer = new byte[1024];
      int read;

      byte c;

      int height = shell.getHeight() - 1;
      int width = shell.getWidth();

      int lCounter = width;
      int y = 0;

      LineBuffer lineBuffer = new LineBuffer(stream, width);

      Mainloop:
      while ((read = lineBuffer.read(buffer)) != -1)
      {
         Bufferloop:
         for (int i = 0; i < read; i++)
         {
            if (--lCounter == 0)
            {
               lineBuffer.seenLine();
               lCounter = width;
               ++y;
            }

            switch (c = buffer[i])
            {
            case '\r':
               i++;
            case '\n':
               lineBuffer.seenLine();
               lCounter = width;
               ++y;

            default:
               if (y == height)
               {
                  out.println();

                  boolean backwards = false;

                  do
                  {
                     String prompt = MOREPROMPT + "[line:" + lineBuffer.getCurrentLine() + "]--";
                     out.print(ShellColor.BOLD, prompt);

                     switch (shell.scan())
                     {
                     case 'e':
                     case 'E':
                     case 'j':
                     case 'J':
                     case 16:
                        lineBuffer.rewindBuffer(height = shell.getHeight() - 1, lineBuffer.getCurrentLine() - 1);
                        lineBuffer.setLineWidth(shell.getWidth());
                        y = 0;
                        shell.clear();
                        continue Mainloop;
                     case 'u':
                     case 'U':
                        lineBuffer.rewindBuffer(height = shell.getHeight() - 1, lineBuffer.getCurrentLine() - height);
                        y = 0;
                        shell.clear();
                        continue Mainloop;

                     case 'y':
                     case 'Y':
                     case 'k':
                     case 'K':
                     case 14:
                     case '\n':
                        y--;
                        height = shell.getHeight() - 1;
                        lineBuffer.setLineWidth(shell.getWidth());

                        shell.cursorLeft(prompt.length());
                        shell.clearLine();
                        continue Bufferloop;
                     case ' ':
                        y = 0;
                        height = shell.getHeight() - 1;
                        lineBuffer.setLineWidth(shell.getWidth());

                        shell.clearLine();
                        shell.cursorLeft(prompt.length());
                        continue Bufferloop;
                     case 'q':
                     case 'Q':
                        out.println();
                        break Mainloop;

                     case '?':
                        backwards = true;
                     case '/':
                        shell.clearLine();
                        shell.cursorLeft(prompt.length());

                        prompt = backwards ? SEARCH_BACKWARDS_PROMPT : SEARCH_FORWARD_PROMPT;

                        out.print(ShellColor.BOLD, prompt);
                        String pattern = shell.promptAndSwallowCR().trim();
                        int result = lineBuffer.findPattern(pattern, backwards);
                        if (result == -1)
                        {
                           shell.clearLine();
                           shell.cursorLeft(prompt.length() + pattern.length());
                           shell.print(ShellColor.RED, PATTERN_NOT_FOUND + pattern);

                           shell.scan();
                           shell.clearLine();
                           shell.cursorLeft(PATTERN_NOT_FOUND.length() + pattern.length());
                        }
                        else
                        {
                           lineBuffer.rewindBuffer(shell.getHeight() - 1, result);
                           y = 0;
                           shell.clear();
                           continue Mainloop;
                        }
                        break;
                     }
                  }
                  while (true);
               }
            }

            out.write(c);
         }
      }
   }


   /**
    * A simple line buffer implementation. Marks every INDEX_MARK_SIZE lines for fast scanning and lower
    * memory usage.
    */
   private static class LineBuffer extends InputStream
   {
      private InputStream stream;
      private StringAppender curr;
      private ArrayList<Integer> index;
      private int bufferPos;
      private int bufferLine;

      private int lineWidth;
      private int lineCounter;

      private static final int INDEX_MARK_SIZE = 50;

      int totalLines = 0;

      private LineBuffer(InputStream stream, int lineWidth)
      {
         this.stream = stream;
         curr = new StringAppender();
         index = new ArrayList<Integer>();
         this.lineWidth = lineWidth;
         this.lineCounter = lineWidth - 1;
      }

      @Override
      public int read() throws IOException
      {
         int c;
         if (inBuffer())
         {
            return curr.charAt(bufferPos++);
         }
         else
         {
            c = stream.read();
            if (c != -1)
            {
               curr.append((char) c);
               bufferPos++;
               if (--lineCounter == 0 || c == '\n')
               {
                  lineCounter = lineWidth - 1;
                  markLine();
               }
            }
            return c;
         }
      }

      public void write(byte b)
      {
         if (!inBuffer())
         {
            curr.append((char) b);
         }
      }

      public void seenLine()
      {
         bufferLine++;
      }

      public void markLine()
      {
         if (++totalLines % INDEX_MARK_SIZE == 0)
         {
            index.add(curr.length());
         }
      }

      public int getCurrentLine()
      {
         return bufferLine;
      }

      public void setLineWidth(int lineWidth)
      {
         this.lineWidth = lineWidth;
      }

      public int findLine(int line)
      {
         int idxMark = line / INDEX_MARK_SIZE;

         if (idxMark > index.size())
         {
            return curr.length() - 1;
         }
         else
         {
            int cursor = idxMark == 0 ? 0 : index.get(idxMark - 1);
            int currLine = idxMark * INDEX_MARK_SIZE;
            int lCount = lineWidth;

            while (cursor < curr.length() && currLine != line)
            {
               switch (curr.charAt(cursor++))
               {
               case '\r':
                  cursor++;
               case '\n':
                  lCount = lineWidth;
                  currLine++;
               }

               if (--lCount == 0)
               {
                  currLine++;
                  lCount = lineWidth;
               }
            }

            return cursor;
         }
      }

      public int findPattern(String pattern, boolean backwards) throws IOException
      {
         Pattern p = Pattern.compile(".*" + pattern + ".*");
         int currentBuffer = bufferPos;
         int currentLine = bufferLine;

         int startLine;
         int cursor = 0;
         if (backwards)
         {
            bufferLine = 0;
            startLine = 0;
            bufferPos = 0;
         }
         else
         {
            cursor = startLine = bufferPos = findLine(bufferLine);
         }

         int line = bufferLine;
         int lCount = lineWidth;

         byte[] buffer = new byte[1024];
         int read;

         while ((read = read(buffer)) != -1)
         {
            for (int i = 0; i < read; i++)
            {
               cursor++;

               switch (buffer[i])
               {
               case '\r':
                  i++;
               case '\n':

                  String l = new String(curr.getChars(startLine, cursor - startLine - 1));
                  if (p.matcher(l).matches())
                  {
                     return line;
                  }
                  line++;
                  startLine = cursor;
               }

               if (--lCount == 0)
               {
                  line++;
                  lCount = lineWidth;
               }
            }
         }

         bufferPos = currentBuffer;
         bufferLine = currentLine;
         return -1;
      }


      public void rewindBuffer(int height, int toLine)
      {
         int renderFrom = toLine - height;
         if (renderFrom < 0)
         {
            bufferLine = 0;
            bufferPos = 0;
         }
         else
         {
            bufferPos = findLine(renderFrom);
            bufferLine = renderFrom;
         }
      }

      public boolean inBuffer()
      {
         return bufferPos < curr.length();
      }
   }
}