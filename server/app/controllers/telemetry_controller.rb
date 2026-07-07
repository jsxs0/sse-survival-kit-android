class TelemetryController < ApplicationController
  # A tick per second; a heartbeat event rides along every HEARTBEAT_EVERY ticks.
  HEARTBEAT_EVERY = 5

  # The raw-stream form (a proc receiving the connection) is the exact stream
  # type whose connection lifecycle rage-rb/rage#248 fixed. This demo runs on
  # the merged result.
  def stream
    last_seen = request.headers["Last-Event-ID"]

    render sse: ->(conn) {
      # Comment lines: valid SSE, ignored by parsers, readable by humans.
      conn.write(": synthetic factory telemetry, every value is generated\n\n")

      # The spec-level reconnection hint. Know your client: okhttp-sse
      # consumes this field without surfacing it, so we also send it as a
      # normal event the mobile client can see.
      conn.write("retry: 3000\n\n")
      conn.write("event: retry-hint\ndata: 3000\n\n")

      # Resume-without-loss: replay everything newer than the id the client
      # last saw. This ring buffer is the server's half of Last-Event-ID.
      EventLog.replay_after(last_seen).each { |frame| conn.write(frame) }

      epoch = Chaos.kill_epoch
      tick = 0

      loop do
        break if conn.closed?
        break if Chaos.kill_epoch != epoch # chaos: severed mid-shift

        begin
          case Chaos.mode
          when :silent
            # The half-open trap, on demand: socket stays open, nothing arrives.
          when :burst
            # The server outpaces the device: 20 events per second.
            20.times { conn.write(EventLog.append(Telemetry.sample)) }
          else
            conn.write(EventLog.append(Telemetry.sample))
            tick += 1
            if (tick % HEARTBEAT_EVERY).zero?
              conn.write("event: heartbeat\ndata: {}\n\n")
            end
          end
        rescue IOError
          # The client hung up between the closed? check and a write: a normal
          # end of shift, not an error.
          break
        end

        sleep 1
      end

      conn.close unless conn.closed?
    }
  end
end
