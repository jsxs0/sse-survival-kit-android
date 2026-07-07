# frozen_string_literal: true

require "time"

# Synthetic factory telemetry. Every value is generated; no real line, plant,
# or machine is represented. The `synthetic: true` field travels on the wire
# so even a screenshot admits it.
module Telemetry
  LINES = %w[A1 A2 B1].freeze

  def self.sample
    {
      line: LINES.sample,
      conveyor_speed_mms: 180 + rand(-15..15),
      motor_temp_c: (62.0 + rand * 6).round(1),
      ts: Time.now.utc.iso8601(3),
      synthetic: true
    }
  end
end
