<template>
  <iframe ref="frame" class="content-html" title="Email content" sandbox="allow-same-origin"
          referrerpolicy="no-referrer" :srcdoc="source" @load="loaded" />
</template>
<script setup>
import { computed, ref, onUnmounted } from 'vue';
import { mailDocument } from '@/utils/mail-html.js';
const props = defineProps({ html: { type: String, required: true }, remoteImages: { type: Boolean, default: false } });
const frame = ref(null);
const source = computed(() => mailDocument(props.html, props.remoteImages));
let observer;
function loaded() {
  observer?.disconnect();
  const body = frame.value?.contentDocument?.body;
  if (!body) return;
  const resize = () => { if (frame.value) frame.value.style.height = `${Math.max(80, body.scrollHeight + 16)}px`; };
  observer = new ResizeObserver(resize); observer.observe(body); resize();
  body.addEventListener('click', event => {
    const link = event.target.closest('a');
    if (!link) return;
    event.preventDefault();
    if (link.href && /^https?:\/\//i.test(link.href)) window.open(link.href, '_blank', 'noopener,noreferrer');
  });
}
onUnmounted(() => observer?.disconnect());
</script>
<style scoped>
.content-html { border: 0; display: block; width: 100%; min-height: 80px; background: white; border-radius: 6px; }
</style>
