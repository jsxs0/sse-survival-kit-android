require "bundler/setup"
require "rage"
Bundler.require(*Rage.groups)

require "rage/all"

Rage.configure do
  # constant across all environments
end

require "rage/setup"

require_relative "../lib/chaos"
require_relative "../lib/event_log"
require_relative "../lib/telemetry"
