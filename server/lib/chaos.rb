# frozen_string_literal: true

# Chaos toggles for the demo. The server misbehaves ON PURPOSE so the client's
# survival machinery has something to survive.
#
# Rage runs one process with cooperative fibers, so plain instance variables
# are safe here: there is no preemption between these reads and writes.
module Chaos
  MODES = %i[normal silent burst].freeze

  @mode = :normal
  @kill_epoch = 0

  class << self
    attr_reader :mode, :kill_epoch

    def mode=(mode)
      raise ArgumentError, "unknown mode #{mode}" unless MODES.include?(mode)

      @mode = mode
    end

    # Sever every open stream mid-shift. Streams watch the epoch and close
    # themselves the moment it moves.
    def kill!
      @kill_epoch += 1
    end

    def status
      { mode: @mode, kill_epoch: @kill_epoch }
    end
  end
end
