# frozen_string_literal: true

require "json"

# Monotonic event ids and a bounded replay window.
#
# `Last-Event-ID` on the client is only half of resume-without-loss. The other
# half lives here: the server must retain a window of recent events to replay.
# The retention window is a promise the app team negotiates with the backend,
# because the client can only resume what the server kept.
module EventLog
  MAX_RETAINED = 500

  @frames = [] # [[id, frame], ...] oldest first
  @next_id = 1

  class << self
    # Wraps a payload in a complete SSE frame with a monotonic id, retains it,
    # and returns the frame ready for the wire.
    def append(payload, event: "telemetry")
      id = @next_id
      @next_id += 1

      frame = "id: #{id}\nevent: #{event}\n" \
              "data: #{payload.to_json}\n\n"
      @frames << [id, frame]
      @frames.shift while @frames.size > MAX_RETAINED

      frame
    end

    # Everything newer than the id the client last saw, oldest first.
    def replay_after(last_event_id)
      cutoff = last_event_id.to_i
      return [] if cutoff.zero?

      @frames.filter_map { |id, frame| frame if id > cutoff }
    end
  end
end
