import { useRef, useState } from 'react'

const ACCEPT = '.pdf,.txt,.md,.markdown,.html,.htm,.csv,.json,.doc,.docx,.ppt,.pptx'

export function Dropzone({
  onFiles,
  disabled,
  hint,
}: {
  onFiles: (files: File[]) => void
  disabled?: boolean
  hint?: string
}) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [over, setOver] = useState(false)

  function pick(list: FileList | null) {
    if (!list || list.length === 0) return
    onFiles(Array.from(list))
  }

  return (
    <div
      className={`dropzone${over ? ' dropzone--over' : ''}${disabled ? ' dropzone--disabled' : ''}`}
      onDragOver={(e) => {
        e.preventDefault()
        if (!disabled) setOver(true)
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        e.preventDefault()
        setOver(false)
        if (!disabled) pick(e.dataTransfer.files)
      }}
      onClick={() => !disabled && inputRef.current?.click()}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if ((e.key === 'Enter' || e.key === ' ') && !disabled) inputRef.current?.click()
      }}
    >
      <input
        ref={inputRef}
        type="file"
        accept={ACCEPT}
        multiple
        hidden
        onChange={(e) => {
          pick(e.target.files)
          e.target.value = ''
        }}
      />
      <strong>Drop files here</strong>
      <span>{hint ?? 'or click to browse — PDF, Word, PowerPoint, HTML, Markdown, CSV, text'}</span>
    </div>
  )
}
