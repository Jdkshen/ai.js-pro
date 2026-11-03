/*
 * aijs-frida - bounded Android native Frida client for AI.js MCP.
 *
 * This is the only file in this helper; it deliberately exposes only
 * status/processes/eval and never spawns, starts a server, or eternalizes a
 * script.  All blocking Frida calls receive a GCancellable driven by a timer
 * thread.  stdout is one JSON document; diagnostics belong on stderr.
 *
 * Upstream: https://github.com/frida/frida (frida-core 17.25.2)
 * License: Frida core is LGPL-2.1-or-later; this source is provided under the
 * same terms.  See README.md for the SDK build and licensing details.
 */

#include "frida-core.h"

#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#define AIJS_DEFAULT_ENDPOINT "127.0.0.1:27042"
#define AIJS_DEFAULT_TIMEOUT_MS 10000
#define AIJS_DEFAULT_DURATION_MS 1000
#define AIJS_MAX_TIMEOUT_MS 15000
#define AIJS_MAX_DURATION_MS 10000
#define AIJS_MAX_PROCESSES 500
#define AIJS_MAX_MESSAGES 100
#define AIJS_MAX_MESSAGE_BYTES (1024 * 1024)
#define AIJS_CLEANUP_MS 1500
#define AIJS_MESSAGE_RESERVE 160

typedef struct
{
  gchar *operation;
  gchar *endpoint;
  gchar *script;
  gchar *token;
  guint pid;
  gboolean has_pid;
  guint duration_ms;
  guint timeout_ms;
} Request;

typedef struct
{
  guint pid;
  gchar *name;
} ProcessInfo;

typedef struct
{
  gboolean ok;
  gboolean timed_out;
  gboolean truncated;
  gchar *error;
  gchar *cleanup_error;
  gchar *operation;
  gchar *endpoint;
  gchar *version;
  gchar *device_id;
  gchar *device_name;
  gboolean connected;
  guint pid;
  gboolean attached;
  gboolean loaded;
  gboolean detached;
  gchar *detach_reason;
  gchar *crash_summary;
  guint duration_ms;
  guint64 message_bytes;
  GPtrArray *messages;  /* gchar* raw JSON records */
  GPtrArray *processes; /* ProcessInfo* */
  gint process_total;
} Result;

/* ------------------------------------------------------------------------- */
/* Small output helpers: JSON-GLib is used for escaping, never for stdout.   */

static gchar *
json_quote (const gchar *value)
{
  JsonNode *node;
  gchar *quoted;

  node = json_node_alloc ();
  json_node_init_string (node, value != NULL ? value : "");
  quoted = json_to_string (node, FALSE);
  json_node_free (node);

  return quoted;
}

static void
append_key (GString *out,
            gboolean *first,
            const gchar *key)
{
  gchar *quoted;

  if (!*first)
    g_string_append_c (out, ',');
  *first = FALSE;

  quoted = json_quote (key);
  g_string_append (out, quoted);
  g_free (quoted);
  g_string_append_c (out, ':');
}

static void
append_string_value (GString *out,
                     const gchar *value)
{
  gchar *quoted;

  if (value == NULL)
  {
    g_string_append (out, "null");
    return;
  }

  quoted = json_quote (value);
  g_string_append (out, quoted);
  g_free (quoted);
}

static void
append_uint_value (GString *out,
                   guint64 value)
{
  gchar *text = g_strdup_printf ("%" G_GUINT64_FORMAT, value);
  g_string_append (out, text);
  g_free (text);
}

static void
append_bool_value (GString *out,
                   gboolean value)
{
  g_string_append (out, value ? "true" : "false");
}

/* ------------------------------------------------------------------------- */
/* Cancellable timer.  It holds its own refs; the main thread joins it before */
/* releasing any of those refs.  g_cancellable_cancel and g_main_loop_quit are */
/* both safe from a non-main thread.                                         */

typedef struct
{
  GCancellable *cancellable;
  GMutex mutex;
  GCond cond;
  gboolean done;
  gboolean fired;
  GMainLoop *loop;
  GThread *thread;
  guint timeout_ms;
} Deadline;

static gpointer
deadline_thread (gpointer data)
{
  Deadline *d = data;
  gint64 limit;

  limit = g_get_monotonic_time () + ((gint64) d->timeout_ms * 1000);

  g_mutex_lock (&d->mutex);
  while (!d->done)
  {
    gint64 now = g_get_monotonic_time ();

    if (now >= limit)
    {
      d->fired = TRUE;
      g_mutex_unlock (&d->mutex);

      /* Cancel first so a blocked sync call wakes up; also unblock a running
       * main loop used while collecting eval messages. */
      g_cancellable_cancel (d->cancellable);
      if (d->loop != NULL)
        g_main_loop_quit (d->loop);
      return NULL;
    }

    g_cond_wait_until (&d->cond, &d->mutex, limit);
  }
  g_mutex_unlock (&d->mutex);

  return NULL;
}

static void
deadline_start (Deadline *d,
                GCancellable *cancellable,
                guint timeout_ms,
                GMainLoop *loop)
{
  memset (d, 0, sizeof (*d));
  g_mutex_init (&d->mutex);
  g_cond_init (&d->cond);
  d->cancellable = g_object_ref (cancellable);
  d->timeout_ms = timeout_ms;
  d->loop = (loop != NULL) ? g_main_loop_ref (loop) : NULL;
  d->thread = g_thread_new ("aijs-deadline", deadline_thread, d);
}

static gboolean
deadline_stop (Deadline *d)
{
  gboolean fired;

  g_mutex_lock (&d->mutex);
  d->done = TRUE;
  g_cond_broadcast (&d->cond);
  g_mutex_unlock (&d->mutex);

  g_thread_join (d->thread);
  d->thread = NULL;

  fired = d->fired;
  g_clear_object (&d->cancellable);
  if (d->loop != NULL)
  {
    g_main_loop_unref (d->loop);
    d->loop = NULL;
  }
  g_mutex_clear (&d->mutex);
  g_cond_clear (&d->cond);

  return fired;
}

/* One bounded cleanup step.  Cleanup uses a fresh cancellable so the main
 * operation's deadline cannot turn unload/detach/close into no-ops. */
static void
cleanup_begin (GCancellable **cancellable,
               Deadline *deadline)
{
  *cancellable = g_cancellable_new ();
  deadline_start (deadline, *cancellable, AIJS_CLEANUP_MS, NULL);
}

static void
cleanup_end (GCancellable **cancellable,
             Deadline *deadline,
             const gchar *step,
             gboolean was_cancelled,
             GError **error,
             Result *result)
{
  gboolean fired = deadline_stop (deadline);

  if (*error != NULL)
  {
    gchar *next = g_strdup_printf ("%s: %s", step, (*error)->message);
    if (result->cleanup_error == NULL)
      result->cleanup_error = next;
    else
    {
      gchar *joined = g_strdup_printf ("%s; %s", result->cleanup_error, next);
      g_free (result->cleanup_error);
      g_free (next);
      result->cleanup_error = joined;
    }
    g_clear_error (error);
  }
  else if (was_cancelled || fired)
  {
    gchar *next = g_strdup_printf ("%s: timed out after %u ms", step, AIJS_CLEANUP_MS);
    if (result->cleanup_error == NULL)
      result->cleanup_error = next;
    else
    {
      gchar *joined = g_strdup_printf ("%s; %s", result->cleanup_error, next);
      g_free (result->cleanup_error);
      g_free (next);
      result->cleanup_error = joined;
    }
  }

  g_clear_object (cancellable);
}

/* ------------------------------------------------------------------------- */
/* Request parsing.                                                          */

static gboolean
set_error (gchar **error,
           const gchar *format,
           ...)
{
  va_list args;

  g_free (*error);
  va_start (args, format);
  *error = g_strdup_vprintf (format, args);
  va_end (args);

  return FALSE;
}

static gboolean
read_member_string (JsonObject *object,
                    const gchar *name,
                    gboolean required,
                    gchar **out,
                    gchar **error)
{
  JsonNode *node;

  *out = NULL;

  if (!json_object_has_member (object, name))
  {
    if (required)
      return set_error (error, "request.%s is required", name);
    return TRUE;
  }

  node = json_object_get_member (object, name);
  if (!JSON_NODE_HOLDS_VALUE (node) || json_node_get_value_type (node) != G_TYPE_STRING)
    return set_error (error, "request.%s must be a string", name);

  *out = g_strdup (json_node_get_string (node));
  if (required && (*out)[0] == '\0')
    return set_error (error, "request.%s must not be empty", name);

  return TRUE;
}

static gboolean
read_member_uint (JsonObject *object,
                  const gchar *name,
                  gboolean required,
                  gboolean *present,
                  guint *out,
                  gchar **error)
{
  JsonNode *node;
  gint64 value;

  *present = FALSE;
  *out = 0;

  if (!json_object_has_member (object, name))
  {
    if (required)
      return set_error (error, "request.%s is required", name);
    return TRUE;
  }

  node = json_object_get_member (object, name);
  if (!JSON_NODE_HOLDS_VALUE (node) || json_node_get_value_type (node) != G_TYPE_INT64)
    return set_error (error, "request.%s must be an integer", name);

  value = json_node_get_int (node);
  if (value < 0 || (guint64) value > G_MAXUINT)
    return set_error (error, "request.%s is out of range", name);

  *present = TRUE;
  *out = (guint) value;
  return TRUE;
}

static gboolean
parse_request (const gchar *path,
               Request *request,
               gchar **error)
{
  gchar *data = NULL;
  gsize data_size = 0;
  JsonParser *parser = NULL;
  JsonNode *root;
  JsonObject *object;
  GError *local_error = NULL;
  gboolean pid_present = FALSE;
  gboolean duration_present = FALSE;
  gboolean timeout_present = FALSE;
  guint value;

  memset (request, 0, sizeof (*request));
  request->duration_ms = AIJS_DEFAULT_DURATION_MS;
  request->timeout_ms = AIJS_DEFAULT_TIMEOUT_MS;

  if (!g_file_get_contents (path, &data, &data_size, &local_error))
  {
    set_error (error, "cannot read request: %s", local_error->message);
    g_clear_error (&local_error);
    return FALSE;
  }

  parser = json_parser_new ();
  if (!json_parser_load_from_data (parser, data, (gssize) data_size, &local_error))
  {
    set_error (error, "invalid request JSON: %s", local_error->message);
    g_clear_error (&local_error);
    g_object_unref (parser);
    g_free (data);
    return FALSE;
  }
  g_free (data);

  root = json_parser_get_root (parser);
  if (!JSON_NODE_HOLDS_OBJECT (root))
  {
    set_error (error, "request must be a JSON object");
    g_object_unref (parser);
    return FALSE;
  }
  object = json_node_get_object (root);

  if (!read_member_string (object, "operation", TRUE, &request->operation, error) ||
      !read_member_string (object, "endpoint", FALSE, &request->endpoint, error) ||
      !read_member_string (object, "script", FALSE, &request->script, error) ||
      !read_member_string (object, "token", FALSE, &request->token, error) ||
      !read_member_uint (object, "pid", FALSE, &pid_present, &request->pid, error) ||
      !read_member_uint (object, "durationMs", FALSE, &duration_present, &value, error))
  {
    g_object_unref (parser);
    return FALSE;
  }
  if (duration_present)
    request->duration_ms = value;

  if (!read_member_uint (object, "timeoutMs", FALSE, &timeout_present, &value, error))
  {
    g_object_unref (parser);
    return FALSE;
  }
  if (timeout_present)
    request->timeout_ms = value;

  request->has_pid = pid_present;

  if (strcmp (request->operation, "status") != 0 &&
      strcmp (request->operation, "processes") != 0 &&
      strcmp (request->operation, "eval") != 0)
  {
    set_error (error, "request.operation must be one of status, processes, eval");
    g_object_unref (parser);
    return FALSE;
  }

  if (request->endpoint == NULL)
    request->endpoint = g_strdup (AIJS_DEFAULT_ENDPOINT);
  if (request->endpoint[0] == '\0' || strlen (request->endpoint) > 512)
  {
    set_error (error, "request.endpoint must be 1..512 characters");
    g_object_unref (parser);
    return FALSE;
  }

  if (request->duration_ms < 1 || request->duration_ms > AIJS_MAX_DURATION_MS)
  {
    set_error (error, "request.durationMs must be 1..%d", AIJS_MAX_DURATION_MS);
    g_object_unref (parser);
    return FALSE;
  }
  if (request->timeout_ms < 1 || request->timeout_ms > AIJS_MAX_TIMEOUT_MS)
  {
    set_error (error, "request.timeoutMs must be 1..%d", AIJS_MAX_TIMEOUT_MS);
    g_object_unref (parser);
    return FALSE;
  }

  if (strcmp (request->operation, "eval") == 0)
  {
    if (!pid_present || request->pid == 0)
    {
      set_error (error, "request.pid must be a positive integer for eval");
      g_object_unref (parser);
      return FALSE;
    }
    if (request->script == NULL || request->script[0] == '\0')
    {
      set_error (error, "request.script is required for eval");
      g_object_unref (parser);
      return FALSE;
    }
  }

  g_object_unref (parser);
  return TRUE;
}

static void
request_clear (Request *request)
{
  g_free (request->operation);
  g_free (request->endpoint);
  g_free (request->token);
  g_free (request->script);
}

static void
result_clear (Result *result)
{
  g_free (result->error);
  g_free (result->cleanup_error);
  g_free (result->operation);
  g_free (result->endpoint);
  g_free (result->version);
  g_free (result->device_id);
  g_free (result->device_name);
  g_free (result->detach_reason);
  g_free (result->crash_summary);
  if (result->messages != NULL)
    g_ptr_array_unref (result->messages);
  if (result->processes != NULL)
  {
    guint i;
    for (i = 0; i < result->processes->len; i++)
    {
      ProcessInfo *info = g_ptr_array_index (result->processes, i);
      g_free (info->name);
      g_free (info);
    }
    g_ptr_array_unref (result->processes);
  }
}

/* ------------------------------------------------------------------------- */
/* Output.  The top-level object is always complete, including on validation */
/* failures.  Extra operation-specific members are stable and documented.    */

static gchar *
build_status (Result *result)
{
  GString *out = g_string_new ("{");
  gboolean first = TRUE;

  append_key (out, &first, "version");
  append_string_value (out, result->version);
  append_key (out, &first, "endpoint");
  append_string_value (out, result->endpoint);
  append_key (out, &first, "connected");
  append_bool_value (out, result->connected);
  append_key (out, &first, "deviceId");
  append_string_value (out, result->device_id);
  append_key (out, &first, "deviceName");
  append_string_value (out, result->device_name);

  if (result->operation != NULL && strcmp (result->operation, "eval") == 0)
  {
    append_key (out, &first, "pid");
    append_uint_value (out, result->pid);
    append_key (out, &first, "attached");
    append_bool_value (out, result->attached);
    append_key (out, &first, "loaded");
    append_bool_value (out, result->loaded);
    append_key (out, &first, "detached");
    append_bool_value (out, result->detached);
    append_key (out, &first, "detachReason");
    append_string_value (out, result->detach_reason);
    append_key (out, &first, "crashSummary");
    append_string_value (out, result->crash_summary);
  }

  g_string_append_c (out, '}');
  return g_string_free (out, FALSE);
}

static void
emit_result (Result *result)
{
  GString *out = g_string_new ("{");
  gboolean first = TRUE;
  gchar *status = build_status (result);
  guint i;

  append_key (out, &first, "ok");
  append_bool_value (out, result->ok);
  append_key (out, &first, "error");
  append_string_value (out, result->error);
  append_key (out, &first, "status");
  g_string_append (out, status);
  g_free (status);
  append_key (out, &first, "messages");
  g_string_append_c (out, '[');
  for (i = 0; i < result->messages->len; i++)
  {
    if (i != 0)
      g_string_append_c (out, ',');
    g_string_append (out, g_ptr_array_index (result->messages, i));
  }
  g_string_append_c (out, ']');
  append_key (out, &first, "truncated");
  append_bool_value (out, result->truncated);
  append_key (out, &first, "timedOut");
  append_bool_value (out, result->timed_out);
  append_key (out, &first, "operation");
  append_string_value (out, result->operation);
  append_key (out, &first, "cleanupError");
  append_string_value (out, result->cleanup_error);
  append_key (out, &first, "messageBytes");
  append_uint_value (out, result->message_bytes);

  if (result->operation != NULL && strcmp (result->operation, "processes") == 0)
  {
    append_key (out, &first, "processes");
    g_string_append_c (out, '[');
    for (i = 0; i < result->processes->len; i++)
    {
      ProcessInfo *info = g_ptr_array_index (result->processes, i);
      if (i != 0)
        g_string_append_c (out, ',');
      g_string_append_c (out, '{');
      {
        gboolean process_first = TRUE;
        append_key (out, &process_first, "pid");
        append_uint_value (out, info->pid);
        append_key (out, &process_first, "name");
        append_string_value (out, info->name);
      }
      g_string_append_c (out, '}');
    }
    g_string_append_c (out, ']');
    append_key (out, &first, "processCount");
    append_uint_value (out, (guint) result->processes->len);
    append_key (out, &first, "processTotal");
    append_uint_value (out, (guint) (result->process_total < 0 ? 0 : result->process_total));
  }

  if (result->operation != NULL && strcmp (result->operation, "eval") == 0)
  {
    append_key (out, &first, "pid");
    append_uint_value (out, result->pid);
    append_key (out, &first, "durationMs");
    append_uint_value (out, result->duration_ms);
  }

  g_string_append_c (out, '}');

  fwrite (out->str, 1, out->len, stdout);
  fputc ('\n', stdout);
  fflush (stdout);
  g_string_free (out, TRUE);
}

static void
result_init (Result *result,
             Request *request)
{
  memset (result, 0, sizeof (*result));
  result->operation = g_strdup (request->operation);
  result->endpoint = g_strdup (request->endpoint);
  result->pid = request->pid;
  result->duration_ms = request->duration_ms;
  result->messages = g_ptr_array_new_with_free_func (g_free);
  result->processes = g_ptr_array_new ();
}

/* ------------------------------------------------------------------------- */
/* Frida session message collection.                                         */

typedef struct
{
  GMutex mutex;
  GPtrArray *items; /* gchar* compact JSON objects */
  gsize bytes;
  guint seen;
  gboolean truncated;
} Collector;

static void
collector_init (Collector *collector)
{
  g_mutex_init (&collector->mutex);
  collector->items = g_ptr_array_new_with_free_func (g_free);
  collector->bytes = 0;
  collector->seen = 0;
  collector->truncated = FALSE;
}

static void
collector_clear (Collector *collector)
{
  g_mutex_lock (&collector->mutex);
  g_ptr_array_unref (collector->items);
  collector->items = NULL;
  g_mutex_unlock (&collector->mutex);
  g_mutex_clear (&collector->mutex);
}

static gchar *
make_invalid_message (const gchar *raw,
                      gsize raw_bytes,
                      const gchar *reason)
{
  JsonObject *object = json_object_new ();
  JsonNode *node = json_node_alloc ();
  gchar *serialized;

  json_object_set_string_member (object, "type", "invalid");
  json_object_set_int_member (object, "rawBytes", (gint64) raw_bytes);
  json_object_set_string_member (object, "parseError", reason != NULL ? reason : "invalid message");
  json_node_init_object (node, object);
  serialized = json_to_string (node, FALSE);
  json_node_free (node);

  (void) raw;
  return serialized;
}

static void
on_message (FridaScript *script,
            const gchar *message,
            GBytes *data,
            gpointer user_data)
{
  Collector *collector = user_data;
  const gchar *raw = (message != NULL) ? message : "";
  gsize raw_bytes = strlen (raw);
  gsize remaining;
  gchar *serialized = NULL;

  (void) script;

  g_mutex_lock (&collector->mutex);

  collector->seen++;

  if (collector->items->len >= AIJS_MAX_MESSAGES)
  {
    collector->truncated = TRUE;
    goto beach;
  }

  if (collector->bytes >= AIJS_MAX_MESSAGE_BYTES)
  {
    collector->truncated = TRUE;
    goto beach;
  }
  remaining = AIJS_MAX_MESSAGE_BYTES - collector->bytes;
  if (raw_bytes > remaining || remaining - raw_bytes < AIJS_MESSAGE_RESERVE)
  {
    collector->truncated = TRUE;
    goto beach;
  }

  {
    JsonParser *parser = json_parser_new ();
    GError *error = NULL;

    if (!json_parser_load_from_data (parser, raw, -1, &error))
    {
      serialized = make_invalid_message (raw, raw_bytes, error->message);
      g_clear_error (&error);
    }
    else
    {
      JsonNode *root = json_parser_get_root (parser);

      if (!JSON_NODE_HOLDS_OBJECT (root))
      {
        serialized = make_invalid_message (raw, raw_bytes, "message root is not an object");
      }
      else
      {
        JsonNode *copy = json_node_copy (root);
        JsonObject *object = json_node_get_object (copy);

        if (data != NULL)
        {
          JsonObject *binary = json_object_new ();
          JsonNode *binary_node = json_node_alloc ();

          /* Binary bytes are deliberately not copied or base64-encoded. */
          json_object_set_int_member (binary, "bytes", (gint64) g_bytes_get_size (data));
          json_object_set_boolean_member (binary, "included", FALSE);
          json_node_init_object (binary_node, binary);
          json_object_set_member (object, "binary", binary_node);
        }

        serialized = json_to_string (copy, FALSE);
        json_node_free (copy);
      }
    }
    g_object_unref (parser);
  }

  if (serialized == NULL)
  {
    collector->truncated = TRUE;
    goto beach;
  }

  if (collector->bytes + strlen (serialized) > AIJS_MAX_MESSAGE_BYTES)
  {
    collector->truncated = TRUE;
    g_free (serialized);
    serialized = NULL;
    goto beach;
  }

  collector->bytes += strlen (serialized);
  g_ptr_array_add (collector->items, serialized);

beach:
  g_mutex_unlock (&collector->mutex);
}

/* ------------------------------------------------------------------------- */
/* Eval runtime.                                                             */

typedef struct
{
  GMainLoop *loop;
  GMutex mutex;
  gboolean duration_done;
  gboolean detached;
  gchar *detach_reason;
  gchar *crash_summary;
} Runtime;

static gboolean
on_duration_elapsed (gpointer data)
{
  Runtime *runtime = data;
  runtime->duration_done = TRUE;
  g_main_loop_quit (runtime->loop);
  return G_SOURCE_REMOVE;
}

static gboolean
on_cancelled (GCancellable *cancellable, gpointer data)
{
  (void) cancellable;
  g_main_loop_quit (data);
  return G_SOURCE_REMOVE;
}

static void
on_detached (FridaSession *session,
             FridaSessionDetachReason reason,
             FridaCrash *crash,
             gpointer user_data)
{
  Runtime *runtime = user_data;
  gchar *reason_text;

  (void) session;

  g_mutex_lock (&runtime->mutex);
  if (runtime->detached)
  {
    g_mutex_unlock (&runtime->mutex);
    return;
  }

  runtime->detached = TRUE;
  reason_text = g_enum_to_string (FRIDA_TYPE_SESSION_DETACH_REASON, reason);
  runtime->detach_reason = g_strdup (reason_text != NULL ? reason_text : "unknown");
  g_free (reason_text);

  if (crash != NULL)
  {
    const gchar *summary = frida_crash_get_summary (crash);
    if (summary != NULL)
      runtime->crash_summary = g_strdup (summary);
  }
  g_mutex_unlock (&runtime->mutex);

  g_main_loop_quit (runtime->loop);
}

static gboolean
runtime_get_detached (Runtime *runtime,
                      gchar **detach_reason,
                      gchar **crash_summary)
{
  gboolean detached;

  g_mutex_lock (&runtime->mutex);
  detached = runtime->detached;
  if (detach_reason != NULL)
    *detach_reason = g_strdup (runtime->detach_reason);
  if (crash_summary != NULL)
    *crash_summary = g_strdup (runtime->crash_summary);
  g_mutex_unlock (&runtime->mutex);

  return detached;
}

/* ------------------------------------------------------------------------- */
/* Operations.                                                               */

static gboolean
open_remote (Request *request,
             Result *result,
             GCancellable *cancellable,
             FridaDeviceManager **out_manager,
             FridaDevice **out_device,
             gchar **error)
{
  FridaDeviceManager *manager;
  FridaDevice *device;
  GHashTable *parameters;
  GError *local_error = NULL;

  manager = frida_device_manager_new ();
  if (manager == NULL)
    return set_error (error, "cannot create Frida device manager");

  FridaRemoteDeviceOptions *options = frida_remote_device_options_new ();
  if (request->token != NULL)
    frida_remote_device_options_set_token (options, request->token);
  device = frida_device_manager_add_remote_device_sync (manager,
      request->endpoint, options, cancellable, &local_error);
  frida_unref (options);
  if (device == NULL)
  {
    set_error (error, "connect %s: %s", request->endpoint, local_error->message);
    g_clear_error (&local_error);
    frida_device_manager_close_sync (manager, cancellable, NULL);
    frida_unref (manager);
    return FALSE;
  }

  result->device_id = g_strdup (frida_device_get_id (device));
  result->device_name = g_strdup (frida_device_get_name (device));

  /* add_remote_device may be lazy; querying parameters proves the endpoint
   * actually answered before we report connected=true. */
  parameters = frida_device_query_system_parameters_sync (device, cancellable, &local_error);
  if (parameters == NULL)
  {
    set_error (error, "connect %s: %s", request->endpoint, local_error->message);
    g_clear_error (&local_error);
    frida_unref (device);
    frida_device_manager_close_sync (manager, cancellable, NULL);
    frida_unref (manager);
    return FALSE;
  }
  g_hash_table_unref (parameters);

  result->connected = TRUE;
  *out_manager = manager;
  *out_device = device;
  return TRUE;
}

static gboolean
run_processes (FridaDevice *device,
               GCancellable *cancellable,
               Result *result,
               gchar **error)
{
  FridaProcessList *list;
  GError *local_error = NULL;
  gint total;
  gint returned;
  gint i;

  list = frida_device_enumerate_processes_sync (device, NULL, cancellable, &local_error);
  if (list == NULL)
  {
    set_error (error, "enumerate processes: %s", local_error->message);
    g_clear_error (&local_error);
    return FALSE;
  }

  total = frida_process_list_size (list);
  returned = MIN (total, AIJS_MAX_PROCESSES);
  result->process_total = total;
  if (total > AIJS_MAX_PROCESSES)
    result->truncated = TRUE;

  for (i = 0; i < returned; i++)
  {
    FridaProcess *process;
    ProcessInfo *info;

    process = frida_process_list_get (list, i);
    if (process == NULL)
      continue;

    info = g_new0 (ProcessInfo, 1);
    info->pid = frida_process_get_pid (process);
    info->name = g_strdup (frida_process_get_name (process));
    g_ptr_array_add (result->processes, info);

    frida_unref (process);
  }

  frida_unref (list);
  return TRUE;
}

static gboolean
run_eval (FridaDevice *device,
          Request *request,
          Result *result,
          GCancellable *cancellable,
          gboolean *timed_out,
          Deadline *deadline,
          gchar **error)
{
  FridaSession *session = NULL;
  FridaScript *script = NULL;
  FridaScriptOptions *options = NULL;
  Collector collector;
  Runtime runtime;
  GSource *cancelled_source = NULL;
  GError *local_error = NULL;
  gboolean connected_signal = FALSE;
  guint duration_source = 0;
  gboolean success = FALSE;

  memset (&runtime, 0, sizeof (runtime));
  g_mutex_init (&runtime.mutex);
  collector_init (&collector);

  runtime.loop = g_main_loop_new (NULL, FALSE);
  cancelled_source = g_cancellable_source_new (cancellable);
  g_source_set_callback (cancelled_source, G_SOURCE_FUNC (on_cancelled), runtime.loop, NULL);
  g_source_attach (cancelled_source, NULL);

  session = frida_device_attach_sync (device, request->pid, NULL, cancellable, &local_error);
  if (session == NULL)
  {
    set_error (error, "attach pid %u: %s", request->pid, local_error->message);
    g_clear_error (&local_error);
    goto beach;
  }
  result->attached = TRUE;

  if (frida_session_is_detached (session))
  {
    set_error (error, "attach pid %u: session detached immediately", request->pid);
    goto beach;
  }

  g_signal_connect (session, "detached", G_CALLBACK (on_detached), &runtime);
  connected_signal = TRUE;

  options = frida_script_options_new ();
  frida_script_options_set_name (options, "aijs-frida");
  frida_script_options_set_runtime (options, FRIDA_SCRIPT_RUNTIME_QJS);

  script = frida_session_create_script_sync (session, request->script, options,
      cancellable, &local_error);
  if (script == NULL)
  {
    set_error (error, "create script: %s", local_error->message);
    g_clear_error (&local_error);
    goto beach;
  }

  g_signal_connect (script, "message", G_CALLBACK (on_message), &collector);

  frida_script_load_sync (script, cancellable, &local_error);
  if (local_error != NULL)
  {
    set_error (error, "load script: %s", local_error->message);
    g_clear_error (&local_error);
    goto beach;
  }
  result->loaded = TRUE;

  {
    gchar *reason = NULL;
    gboolean detached = runtime_get_detached (&runtime, &reason, NULL);

    if (detached || frida_session_is_detached (session))
    {
      set_error (error, "session detached before message collection: %s",
          reason != NULL ? reason : "unknown");
      g_free (reason);
      goto beach;
    }
    g_free (reason);
  }

  duration_source = g_timeout_add (request->duration_ms, on_duration_elapsed, &runtime);
  if (duration_source == 0)
  {
    set_error (error, "cannot schedule duration timer");
    goto beach;
  }
  g_main_loop_run (runtime.loop);

  if (!runtime.duration_done)
  {
    if (duration_source != 0)
    {
      g_source_remove (duration_source);
      duration_source = 0;
    }

    {
      gchar *reason = NULL;
      if (runtime_get_detached (&runtime, &reason, NULL))
      {
        set_error (error, "session detached: %s", reason != NULL ? reason : "unknown");
        g_free (reason);
        goto beach;
      }
      g_free (reason);
    }

    if (g_cancellable_is_cancelled (cancellable))
    {
      *timed_out = TRUE;
      set_error (error, "operation timed out after %u ms", request->timeout_ms);
      goto beach;
    }

    set_error (error, "message loop stopped unexpectedly");
    goto beach;
  }

  duration_source = 0;

  {
    gchar *reason = NULL;
    if (runtime_get_detached (&runtime, &reason, NULL))
    {
      set_error (error, "session detached: %s", reason != NULL ? reason : "unknown");
      g_free (reason);
      goto beach;
    }
    g_free (reason);
  }

  if (frida_session_is_detached (session))
  {
    set_error (error, "session detached before duration completed");
    goto beach;
  }

  success = TRUE;

beach:
  /* Stop the operation deadline before cleanup; cleanup has its own bounded
   * timer and must not turn a completed eval into a false timeout. */
  if (deadline->thread != NULL)
  {
    gboolean fired = deadline_stop (deadline);
    if (fired || g_cancellable_is_cancelled (cancellable))
      *timed_out = TRUE;
  }

  if (script != NULL)
    g_signal_handlers_disconnect_by_data (script, &collector);

  /* Snapshot collector state before cleanup can run more callbacks. */
  g_mutex_lock (&collector.mutex);
  result->message_bytes = collector.bytes;
  if (collector.truncated)
    result->truncated = TRUE;
  {
    guint i;
    for (i = 0; i < collector.items->len; i++)
      g_ptr_array_add (result->messages, g_strdup (g_ptr_array_index (collector.items, i)));
  }
  g_mutex_unlock (&collector.mutex);

  if (cancelled_source != NULL)
  {
    if (!g_source_is_destroyed (cancelled_source))
      g_source_destroy (cancelled_source);
    g_source_unref (cancelled_source);
    cancelled_source = NULL;
  }

  if (connected_signal)
    g_signal_handlers_disconnect_by_data (session, &runtime);

  if (script != NULL)
  {
    GCancellable *cleanup_cancellable;
    Deadline cleanup_deadline;

    cleanup_begin (&cleanup_cancellable, &cleanup_deadline);
    frida_script_unload_sync (script, cleanup_cancellable, &local_error);
    cleanup_end (&cleanup_cancellable, &cleanup_deadline, "unload script",
        g_cancellable_is_cancelled (cleanup_cancellable), &local_error, result);
    frida_unref (script);
  }

  if (session != NULL)
  {
    result->detached = frida_session_is_detached (session);
    if (!result->detached)
    {
      GCancellable *cleanup_cancellable;
      Deadline cleanup_deadline;

      cleanup_begin (&cleanup_cancellable, &cleanup_deadline);
      frida_session_detach_sync (session, cleanup_cancellable, &local_error);
      cleanup_end (&cleanup_cancellable, &cleanup_deadline, "detach session",
          g_cancellable_is_cancelled (cleanup_cancellable), &local_error, result);
      result->detached = frida_session_is_detached (session);
    }
    frida_unref (session);
  }

  g_mutex_lock (&runtime.mutex);
  if (runtime.detach_reason != NULL)
    result->detach_reason = g_strdup (runtime.detach_reason);
  if (runtime.crash_summary != NULL)
    result->crash_summary = g_strdup (runtime.crash_summary);
  g_mutex_unlock (&runtime.mutex);

  collector_clear (&collector);
  if (options != NULL)
    g_object_unref (options);
  g_main_loop_unref (runtime.loop);
  g_free (runtime.detach_reason);
  g_free (runtime.crash_summary);
  g_mutex_clear (&runtime.mutex);

  return success;
}

/* ------------------------------------------------------------------------- */

static int
finish (Result *result)
{
  if (result->cleanup_error != NULL)
  {
    if (result->error == NULL)
      result->error = g_strdup_printf ("cleanup: %s", result->cleanup_error);
    else
    {
      gchar *joined = g_strdup_printf ("%s; cleanup: %s", result->error, result->cleanup_error);
      g_free (result->error);
      result->error = joined;
    }
    result->ok = FALSE;
  }

  emit_result (result);
  return result->ok ? 0 : 1;
}

int
main (int argc,
      char *argv[])
{
  Request request;
  Result result;
  gchar *parse_error = NULL;
  FridaDeviceManager *manager = NULL;
  FridaDevice *device = NULL;
  GCancellable *cancellable = NULL;
  Deadline deadline;
  gboolean deadline_active = FALSE;
  gboolean timed_out = FALSE;
  GError *local_error = NULL;

  /* Frida's private GLib must be initialized before JSON-GLib/type allocation. */
  frida_init ();

  if (argc != 2)
  {
    int status;

    memset (&request, 0, sizeof (request));
    request.operation = NULL;
    request.endpoint = g_strdup (AIJS_DEFAULT_ENDPOINT);
    result_init (&result, &request);
    set_error (&parse_error, "usage: aijs-frida <request.json>");
    result.ok = FALSE;
    result.error = parse_error;
    status = finish (&result);
    result_clear (&result);
    g_free (request.endpoint);
    return status;
  }

  if (!parse_request (argv[1], &request, &parse_error))
  {
    Request empty;
    int status;

    memset (&empty, 0, sizeof (empty));
    empty.endpoint = g_strdup (AIJS_DEFAULT_ENDPOINT);
    result_init (&result, &empty);
    g_free (empty.endpoint);
    result.ok = FALSE;
    result.error = parse_error;
    status = finish (&result);
    result_clear (&result);
    request_clear (&request);
    return status;
  }

  result_init (&result, &request);

  result.version = g_strdup (frida_version_string ());

  cancellable = g_cancellable_new ();
  deadline_start (&deadline, cancellable, request.timeout_ms, NULL);
  deadline_active = TRUE;

  if (!open_remote (&request, &result, cancellable, &manager, &device, &parse_error))
  {
    result.error = parse_error;
    parse_error = NULL;
    goto done;
  }

  if (strcmp (request.operation, "status") == 0)
  {
    /* open_remote already proved that the endpoint answered. */
    result.ok = TRUE;
  }
  else if (strcmp (request.operation, "processes") == 0)
  {
    if (run_processes (device, cancellable, &result, &parse_error))
      result.ok = TRUE;
    else
    {
      result.error = parse_error;
      parse_error = NULL;
    }
  }
  else
  {
    gboolean eval_ok = run_eval (device, &request, &result, cancellable,
        &timed_out, &deadline, &parse_error);
    gboolean eval_timed_out = timed_out || g_cancellable_is_cancelled (cancellable);

    /* run_eval stops the operation timer before its own cleanup, so avoid a
     * second deadline_stop here when it already ran. */
    if (deadline.thread == NULL)
      deadline_active = FALSE;

    if (eval_ok)
      result.ok = TRUE;
    else
    {
      result.error = parse_error;
      parse_error = NULL;
      if (eval_timed_out)
        result.timed_out = TRUE;
    }
  }

done:
  if (deadline_active)
  {
    gboolean was_cancelled = g_cancellable_is_cancelled (cancellable);
    timed_out = deadline_stop (&deadline);
    deadline_active = FALSE;
    if (timed_out || was_cancelled)
      result.timed_out = TRUE;
    if (result.timed_out)
    {
      result.ok = FALSE;
      if (result.error == NULL)
        result.error = g_strdup_printf ("operation timed out after %u ms", request.timeout_ms);
    }
  }

  if (device != NULL)
    frida_unref (device);

  if (manager != NULL)
  {
    GCancellable *cleanup_cancellable;
    Deadline cleanup_deadline;

    cleanup_begin (&cleanup_cancellable, &cleanup_deadline);
    frida_device_manager_close_sync (manager, cleanup_cancellable, &local_error);
    cleanup_end (&cleanup_cancellable, &cleanup_deadline, "close device manager",
        FALSE, &local_error, &result);
    frida_unref (manager);
  }

  g_clear_object (&cancellable);
  g_free (parse_error);
  request_clear (&request);

  {
    int status = finish (&result);
    result_clear (&result);
    return status;
  }
}
