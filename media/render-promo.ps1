$ErrorActionPreference = 'Stop'
$mediaDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$narrationText = Get-Content -Raw (Join-Path $mediaDirectory 'promo-narration.txt')
$voice = New-Object -ComObject SAPI.SpVoice
$audioStream = New-Object -ComObject SAPI.SpFileStream
$wavePath = Join-Path $mediaDirectory 'promo-narration.wav'
$audioStream.Open($wavePath, 3, $false)
$voice.AudioOutputStream = $audioStream
$voice.Rate = 0
[void]$voice.Speak($narrationText)
$audioStream.Close()

Push-Location $mediaDirectory
try {
    ffmpeg -y -f concat -safe 0 -i promo-images.txt -i promo-narration.wav `
      -f lavfi -t 70 -i 'anullsrc=channel_layout=stereo:sample_rate=48000' `
      -filter_complex "[0:v]scale=-2:960,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=0x17131f,format=yuv420p,subtitles=promo.srt:force_style='FontName=Arial,FontSize=14,PrimaryColour=&H00FFFFFF,OutlineColour=&HCC17131F,BorderStyle=3,Outline=1,Shadow=0,MarginV=35,Alignment=2',tpad=stop_mode=clone:stop_duration=5[v];[1:a]apad=pad_dur=70[n];[n][2:a]amix=inputs=2:duration=longest:weights='1 0'[a]" `
      -map '[v]' -map '[a]' -t 70 -r 30 -c:v libx264 -preset medium -crf 20 `
      -c:a aac -b:a 192k -ar 48000 -movflags +faststart owefolk-promo.mp4
} finally {
    Pop-Location
}
