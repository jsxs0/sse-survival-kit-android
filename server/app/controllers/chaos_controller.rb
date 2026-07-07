class ChaosController < ApplicationController
  def status
    render json: Chaos.status
  end

  def normal
    Chaos.mode = :normal
    render json: Chaos.status
  end

  def silent
    Chaos.mode = :silent
    render json: Chaos.status
  end

  def burst
    Chaos.mode = :burst
    render json: Chaos.status
  end

  def kill
    Chaos.kill!
    render json: Chaos.status
  end
end
