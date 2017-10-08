package org.apache.sling.tail.impl

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.FileAppender
import com.google.gson.stream.JsonWriter
import org.apache.sling.api.SlingHttpServletRequest
import org.apache.sling.api.SlingHttpServletResponse
import org.apache.sling.api.servlets.HttpConstants
import org.apache.sling.api.servlets.SlingAllMethodsServlet
import org.apache.sling.tail.LogFilter
import org.osgi.framework.Constants
import org.osgi.service.component.annotations.Component
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.*
import javax.servlet.Servlet
import javax.servlet.ServletException
import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@Component(
  service = arrayOf(Servlet::class),
  property = arrayOf(Constants.SERVICE_DESCRIPTION + "=Logtail Servlet",
    "sling.servlet.methods=" + HttpConstants.METHOD_GET,
    "sling.servlet.methods=" + HttpConstants.METHOD_POST,
    "sling.servlet.paths=" + "/bin/logtail")
)
class LogtailServlet : SlingAllMethodsServlet() {
  private val log = LoggerFactory.getLogger(this.javaClass)
  private var fileName = ""
  private var errLog: File? = null
  private val filePathMap = HashMap<String, String>()

  private val options: String
    get() {
      val logFiles = HashSet<String>()
      val context = LoggerFactory.getILoggerFactory() as LoggerContext
      for (logger in context.loggerList) {
        val index = logger.iteratorForAppenders()
        while (index.hasNext()) {
          val appender = index.next()
          if (appender is FileAppender<*>) {
            val fileAppender = appender as FileAppender<*>
            val logfilePath = fileAppender.file
            logFiles.add(logfilePath)
          }
        }
      }

      var logFilesHtml = "<option value=\"\"> - Select file - </option>"
      for (logFile in logFiles) {
        val file = File(logFile)
        logFilesHtml += "<option value=\"" + getKey(file) + "\">" + file.name + "</option>"
      }
      return logFilesHtml
    }

  @Throws(ServletException::class, IOException::class)
  override fun service(request: SlingHttpServletRequest, response: SlingHttpServletResponse) {
    if (isAjaxRequest(request)) {

      parseCommand(request, response)

      var randomAccessFile: RandomAccessFile? = null

      try {

        try {
          randomAccessFile = RandomAccessFile(errLog!!, "r")
          log.debug("Tailing file " + fileName + " of length " + randomAccessFile.length())
        } catch (e: Exception) {
          log.error("Error reading " + fileName, e)
          response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
          return
        }

        val json = JsonWriter(response.writer)
        json.beginObject()

        var reverse = false

        var pos = getPositionFromCookie(request)
        if (pos < 0) {
          pos = randomAccessFile.length() - 1
          reverse = true
        } else if (pos > randomAccessFile.length()) {//file rotated
          pos = 0
        }
        val query = getQueryFromCookie(request)

        if (reverse) {
          randomAccessFile.seek(pos)
          if (randomAccessFile.read() == '\n'.toInt()) {
            pos--
            randomAccessFile.seek(pos)
            if (randomAccessFile.read() == '\r'.toInt()) {
              pos--
            }
          }

          json.name("content").beginArray()
          var found = 0
          var sb = StringBuilder()
          var line: String = "dummy line"
          val lines = ArrayList<String>()
          while (found != LINES_TO_TAIL && pos > 0) {
            var eol = false
            randomAccessFile.seek(pos)
            val c = randomAccessFile.read()
            if (c == '\n'.toInt()) {
              found++
              sb = sb.reverse()
              line = sb.toString()
              sb = StringBuilder()
              eol = true
              pos--
              if (pos > 0) {
                randomAccessFile.seek(pos)
                if (randomAccessFile.read() == '\r'.toInt()) {
                  pos--
                }
              }
            } else {
              sb.append(c.toChar())
              pos--
            }

            if (eol) {
              if (filter(line, query)) {
                lines.add(line)
              }
            }
          }

          if (pos < 0) {
            if (filter(line, query)) {
              lines.add(line)
            }
          }
          for (i in lines.size - 1 downTo -1 + 1) {
            json.beginObject().name("line").value(lines[i]).endObject()
          }
          json.endArray()
          json.endObject()
        } else {
          randomAccessFile.seek(pos)
          var line: String?
          var lineCount = 0
          json.name("content").beginArray()
          var read = true
          while (read) {
            val input = StringBuilder()
            var c = -1
            var eol = false

            while (!eol) {
              c = randomAccessFile.read()
              when (c) {
                '\n'.toInt() -> eol = true
                '\r'.toInt() -> {
                  eol = true
                  val cur = randomAccessFile.filePointer
                  if (randomAccessFile.read() != '\n'.toInt()) {
                    randomAccessFile.seek(cur)
                  }
                }
                else -> input.append(c.toChar())
              }
            }

            if (c == -1 && input.length == 0) {
              read = false
              continue
            }
            line = input.toString()
            lineCount++
            if (lineCount == LINES_TO_TAIL) {
              read = false
            }

            if (filter(line, query)) {
              json.beginObject().name("line").value(line).endObject()
            }
          }
          json.endArray()
          json.endObject()
        }

        persistCookie(response, POSITION_COOKIE, randomAccessFile.filePointer.toString())

      } catch (e: Exception) {
        log.error("Error tailing " + fileName, e)
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
      } finally {
        try {
          if (randomAccessFile != null) {
            randomAccessFile.close()
          }
        } catch (e: Exception) {
          log.error("Error closing " + fileName, e)
        }

      }
    } else {
      response.setContentType("text/html; charset=UTF-8")
      val printWriter = response.writer
      printWriter.println("<html>")
      printWriter.println("<head>")
      printWriter.println("<title>Logtail</title>")
      printWriter.println("<script type=\"text/javascript\"> var tailUrl = '/bin/logtail?ajax'; </script>")

      printWriter.println("<link href=\"/libs/sling/logtail-plugin/css/reset-min.css\" rel=\"stylesheet\" type=\"text/css\"></link>")
      printWriter.println("<link href=\"/libs/sling/logtail-plugin/css/jquery-ui.css\" rel=\"stylesheet\" type=\"text/css\"></link>")
      printWriter.println("<link href=\"/libs/sling/logtail-plugin/css/webconsole.css\" rel=\"stylesheet\" type=\"text/css\"></link>")

      printWriter.println("<script type=\"text/javascript\" src=\"/libs/sling/logtail-plugin/js/jquery-1.8.3.js\"></script>")
      printWriter.println("<script type=\"text/javascript\" src=\"/libs/sling/logtail-plugin/js/jquery-ui-1.9.2.js\"></script>")
      printWriter.println("<script type=\"text/javascript\" src=\"/libs/sling/logtail-plugin/js/jquery-ui-i18n-1.7.2.js\"></script>")
      printWriter.println("<script type=\"text/javascript\" src=\"/libs/sling/logtail-plugin/js/jquery.cookies-2.2.0.js\"></script>")
      printWriter.println("<script type=\"text/javascript\" src=\"/libs/sling/logtail-plugin/js/jquery.tablesorter-2.0.3.js\"></script>")
      printWriter.println("<script type=\"text/javascript\" src=\"/libs/sling/logtail-plugin/js/autosize.min.js\"></script>")
      printWriter.println("<script type=\"text/javascript\" src=\"/libs/sling/logtail-plugin/js/support.js\"></script>")

      printWriter.println("<script type=\"text/javascript\" src=\"/libs/sling/logtail-plugin/js/tail.js\"></script>")

      printWriter.println("<link href=\"/libs/sling/logtail-plugin/css/admin_compat.css\" rel=\"stylesheet\" type=\"text/css\"></link>")

      printWriter.println("<link href=\"/libs/sling/logtail-plugin/css/tail.css\" rel=\"stylesheet\" type=\"text/css\"></link>")

      printWriter.println("</head><body class=\"ui-widget\">")
      printWriter.println("<div id=\"main\"><div id=\"content\">")

      printWriter.println("<div class=\"header-cont\">")
      printWriter.println("   <div class=\"header\" style=\"display:none;\">")
      printWriter.println("       <table>")
      printWriter.println("           <tr>")
      printWriter.println("               <td><button class=\"numbering\" title=\"Show Line Numbers\" data-numbers=\"false\">Show Line No.</button></td>")
      printWriter.println("               <td><button class=\"pause\" title=\"Pause\">Pause</button></td>")
      printWriter.println("               <td class=\"longer\"><label>Sync frequency(msec)</label>")
      printWriter.println("                   <button class=\"faster\" title=\"Sync Faster\">-</button>")
      printWriter.println("                   <input id=\"speed\" type=\"text\" value=\"3000\"/>")
      printWriter.println("                   <button class=\"slower\" title=\"Sync Slower\">+</button></td>")
      printWriter.println("               <td><button class=\"tail\" title=\"Unfollow Tail\" data-following=\"true\">Unfollow</button></td>")
      printWriter.println("               <td><button class=\"highlighting\" title=\"Highlight\">Highlight</button></td>")
      printWriter.println("               <td><button class=\"clear\" title=\"Clear Display\">Clear</button></td>")
      printWriter.println("               <td class=\"longer\"><input id=\"filter\" type=\"text\"/><span class=\"filterClear ui-icon ui-icon-close\" title=\"Clear Filter\">&nbsp;</span><button class=\"filter\" title=\"Filter Logs\">Filter</button></td>")
      printWriter.println("               <td><button class=\"refresh\" title=\"Reload Logs\">Reload</button></td>")
      printWriter.println("               <td><button class=\"sizeplus\" title=\"Bigger\">a->A</button></td>")
      printWriter.println("               <td><button class=\"sizeminus\" title=\"Smaller\">A->a</button></td>")
      printWriter.println("               <td><button class=\"top\" title=\"Scroll to Top\">Top</button></td>")
      printWriter.println("               <td><button class=\"bottom\" title=\"Scroll to Bottom\">Bottom</button></td>")
      printWriter.println("           </tr>")
      printWriter.println("           <tr>")
      printWriter.println("               <td class=\"loadingstatus\" colspan=\"2\" data-status=\"inactive\"><ul><li></li></ul></td>")
      printWriter.println("               <td>Tailing &nbsp; <select id=\"logfiles\">$options</select></td>")
      printWriter.println("           </tr>")
      printWriter.println("       </table>")
      printWriter.println("   </div>")
      printWriter.println("   <div class=\"pulldown\" title=\"Click to show options\">&nbsp;==&nbsp;</div>")
      printWriter.println("</div>")
      printWriter.println("")
      printWriter.println("   <div class=\"content\">")
      printWriter.println("")
      printWriter.println("       <div id=\"logarea\"></div>")
      printWriter.println("")
      printWriter.println("   </div>")
      printWriter.println("</div></div>")
      printWriter.println("</body></html>")

    }
  }

  @Throws(ServletException::class, IOException::class)
  protected fun parseCommand(request: HttpServletRequest, response: HttpServletResponse) {
    val cmd = request.getParameter("command") ?: return

    if (cmd == "reset") {
      deleteCookie(response, FILTER_COOKIE)
    } else if (cmd.startsWith("filter:")) {
      val queryStr = cmd.substring(7)
      if (queryStr.length == 0) {
        deleteCookie(response, FILTER_COOKIE)
      } else {
        persistCookie(response, FILTER_COOKIE, queryStr)
        log.info("Filtering on : " + queryStr)
      }
    } else if (cmd.startsWith("file:")) {
      if (fileName != cmd.substring(5)) {
        deleteCookie(response, FILTER_COOKIE)
        deleteCookie(response, POSITION_COOKIE)
        fileName = cmd.substring(5)
        errLog = File(filePathMap[fileName])
        if (!errLog!!.exists()) {
          throw ServletException("File $fileName doesn't exist")
        }
        if (!errLog!!.canRead()) {
          throw ServletException("Cannot read file " + fileName)
        }
      }
    }
  }

  private fun getKey(file: File): String {
    if (!filePathMap.containsKey(file.name)) {
      filePathMap.put(file.name, file.absolutePath)
    }
    return file.name
  }

  protected fun isHtmlRequest(request: HttpServletRequest): Boolean {
    return !isAjaxRequest(request)
  }

  private fun isAjaxRequest(request: HttpServletRequest): Boolean {
    return request.parameterMap.containsKey("ajax")
  }

  private fun filter(str: String, query: Array<LogFilter>): Boolean {
    for (q in query) {
      if (!q.eval(str)) {
        return false
      }
    }
    return true
  }

  private fun deleteCookie(response: HttpServletResponse, name: String) {
    val cookie = Cookie(name, "")
    cookie.maxAge = 0
    response.addCookie(cookie)
    log.debug("Deleting cookie :: " + cookie.name)
  }

  private fun persistCookie(response: HttpServletResponse, name: String, value: String) {
    val cookie = Cookie(name, value)
    //cookie.setPath("/system/console/" + LABEL);
    response.addCookie(cookie)
    log.debug("Adding cookie :: " + cookie.name + " " + cookie.value)
  }

  private fun getQueryFromCookie(request: HttpServletRequest): Array<LogFilter> {
    var conditions = arrayOf<LogFilter>()
    try {
      for (cookie in request.cookies) {
        if (cookie.name == FILTER_COOKIE) {
          val parts = cookie.value.split("&&".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()

          for (i in parts.indices) {
            val part = parts[i]
            conditions[i] = object : LogFilter {
              override fun eval(input: String): Boolean {
                return input.contains(part)
              }

              override fun toString(): String {
                return part
              }
            }
          }
        }
      }
    } catch (e: Exception) {

    }
    return conditions
  }

  private fun getCreatedTimestampFromCookie(request: HttpServletRequest): Long {
    try {
      for (cookie in request.cookies) {
        if (cookie.name == CREATED_COOKIE) {
          return java.lang.Long.parseLong(cookie.value)
        }
      }
    } catch (e: Exception) {

    }

    return -1
  }

  private fun getModifiedTimestampFromCookie(request: HttpServletRequest): Long {
    try {
      for (cookie in request.cookies) {
        if (cookie.name == MODIFIED_COOKIE) {
          return java.lang.Long.parseLong(cookie.value)
        }
      }
    } catch (e: Exception) {

    }

    return -1
  }

  private fun getPositionFromCookie(request: HttpServletRequest): Long {
    try {
      for (cookie in request.cookies) {
        if (cookie.name == POSITION_COOKIE) {
          return java.lang.Long.parseLong(cookie.value)
        }
      }
    } catch (e: Exception) {
      log.debug("Position specified is invalid, Tailing from beginning of the file.", e)
    }

    return -1
  }

  companion object {
    val LABEL = "tail"
    val TITLE = "Tail Logs"
    private val LINES_TO_TAIL = 100
    private val POSITION_COOKIE = "log.tail.position"
    private val FILTER_COOKIE = "log.tail.filter"
    private val MODIFIED_COOKIE = "log.modified"
    private val CREATED_COOKIE = "log.created"
  }
}
