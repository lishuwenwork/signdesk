<script setup>
defineProps({ name: { type: String, default: 'info' } })
const paths = {
  check: 'm5 12 4 4L19 6', grid: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z',
  layers: 'm3 7 9-4 9 4-9 4-9-4Zm0 5 9 4 9-4M3 17l9 4 9-4',
  clock: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM12 6v6l4 2',
  record: 'M7 4h12v16H7M4 8h9M4 12h9M4 16h9',
  settings: 'm9 3-1 3-3 1 1 3-2 2 2 2-1 3 3 1 1 3h6l1-3 3-1-1-3 2-2-2-2 1-3-3-1-1-3ZM15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z',
  plus: 'M12 5v14M5 12h14', more: 'M5 12h.01M12 12h.01M19 12h.01',
  chevron: 'm6 9 6 6 6-6', search: 'M17 10.5a6.5 6.5 0 1 1-13 0 6.5 6.5 0 0 1 13 0Zm-1 5.5 4 4',
  play: 'm8 5 11 7-11 7Z', close: 'm6 6 12 12M6 18 18 6',
  edit: 'm15 4 5 5M4 20l5-1L20 8a2 2 0 0 0-4-4L5 15Z',
  info: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM12 11v6M12 7h.01',
  alert: 'm12 3 10 18H2ZM12 9v5M12 17h.01', key: 'M13 8a5 5 0 1 1-10 0 5 5 0 0 1 10 0Zm-1 4 9 9M16 16l3-3M19 19l3-3',
  refresh: 'M20 7V3l-4 4M4 17v4l4-4M20 7a9 9 0 0 0-15-2M4 17a9 9 0 0 0 15 2',
  template: 'M6 3h12a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2ZM8 8h8M8 12h8M8 16h5',
  pause: 'M8 5v14M16 5v14', arrow: 'M5 12h14m-5-5 5 5-5 5',
  power: 'M12 3v9M7 5a9 9 0 1 0 10 0', trash: 'M4 6h16M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7M14 10v7',
}
</script>
<template><svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path :d="paths[name] || paths.info" /></svg></template>
