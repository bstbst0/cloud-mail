<template>
  <div class="box">
    <div class="header-actions">
      <div class="back-btn" @click="handleBack">
        <Icon icon="material-symbols:arrow-back-rounded" width="20" height="20"/>
      </div>
      <div class="action-pills">
        <div class="pill pill-tonal" v-perm="'email:delete'" @click="handleDelete">
          <Icon icon="material-symbols:delete-outline-rounded" width="18" height="18"/>
          <span>{{ $t('delete') }}</span>
        </div>
        <div v-if="emailStore.contentData.showStar" class="pill" :class="email.isStar ? 'pill-filled' : 'pill-outlined'" @click="changeStar">
          <Icon :icon="email.isStar ? 'material-symbols:star-rounded' : 'material-symbols:star-outline-rounded'" width="18" height="18"/>
          <span>{{ email.isStar ? $t('starred') : $t('star') }}</span>
        </div>
      </div>
    </div>
    <el-scrollbar class="scrollbar">
      <div class="container">
        <div class="email-title">
          {{ email.subject }}
        </div>
        <div class="content">
          <div class="email-info">
            <div>
              <div class="send"><span class="send-source">{{$t('from')}}</span>
                <div class="send-name">
                  <span class="send-name-title">{{ email.name }}</span>
                  <span>&lt;{{ email.sendEmail }}&gt;</span>
                </div>
              </div>
              <div class="receive"><span class="source">{{$t('recipient')}}</span><span class="receive-email">{{  formateReceive(email.recipient) }}</span></div>
              <div class="date">
                <div>{{ formatDetailDate(email.createTime) }}</div>
              </div>
            </div>
            <el-alert v-if="email.status === 3" :closable="false" :title="toMessage(email.message)" class="email-msg" type="error" show-icon />
            <el-alert v-if="email.status === 4" :closable="false" :title="$t('complained')" class="email-msg" type="warning" show-icon />
            <el-alert v-if="email.status === 5" :closable="false" :title="$t('delayed')" class="email-msg" type="warning" show-icon />
          </div>
          <el-scrollbar class="htm-scrollbar" :class="email.attList.length === 0 ? 'bottom-distance' : ''">
            <el-button v-if="email.content && !remoteImages" size="small" @click="remoteImages = true">{{ $t('loadRemoteImages') }}</el-button>
            <el-alert v-if="attachmentError" :title="attachmentError" type="error" :closable="false" />
            <ShadowHtml class="shadow-html" :html="renderedHtml" :remote-images="remoteImages" v-if="email.content" />
            <pre v-else class="email-text" >{{email.text}}</pre>
          </el-scrollbar>
          <div class="att" v-if="email.attList.length > 0">
            <div class="att-title">
              <span>{{$t('attachments')}}</span>
              <span>{{$t('attCount',{total: email.attList.length})}}</span>
            </div>
            <div class="att-box">

              <div class="att-item" v-for="att in email.attList" :key="att.attId">
                <div class="att-icon" @click="showImage(att)">
                  <Icon v-bind="getIconByName(att.filename)" />
                </div>
                <div class="att-name" @click="showImage(att)">
                  {{ att.filename }}
                </div>
                <div class="att-size">{{ formatBytes(att.size) }}</div>
                <div class="opt-icon att-icon">
                  <Icon v-if="isImage(att.filename)" icon="hugeicons:view" width="22" height="22" @click="showImage(att)"/>
                  <button class="attachment-download" type="button" :aria-label="$t('download') + ': ' + att.filename" :disabled="attachmentBusy === att.key" @click="downloadAttachment(att)">
                    <Icon icon="system-uicons:push-down" width="22" height="22"/>
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </el-scrollbar>
    <div class="bottom-actions" v-if="emailStore.contentData.showReply" v-perm="'email:send'">
      <div class="pill pill-tonal" @click="openReply">
        <Icon icon="material-symbols:reply-rounded" width="18" height="18"/>
        <span>{{ $t('reply') }}</span>
      </div>
      <div class="pill pill-tonal" @click="openForward">
        <Icon icon="material-symbols:forward-rounded" width="18" height="18"/>
        <span>{{ $t('forward') }}</span>
      </div>
    </div>
    <el-image-viewer
        v-if="showPreview"
        :url-list="srcList"
        show-progress
        @close="showPreview = false"
    />
  </div>
</template>
<script setup>
import ShadowHtml from '@/components/shadow-html/index.vue'
import {reactive, ref, watch, onMounted, onUnmounted} from "vue";
import {useRouter} from 'vue-router'
import {ElMessage, ElMessageBox} from 'element-plus'
import {emailDelete, emailRead} from "@/request/email.js";
import {Icon} from "@iconify/vue";
import {useEmailStore} from "@/store/email.js";
import {useAccountStore} from "@/store/account.js";
import {formatDetailDate} from "@/utils/day.js";
import {starAdd, starCancel} from "@/request/star.js";
import {getExtName, formatBytes} from "@/utils/file-utils.js";
import {privateAttachmentBlob, safeFilename} from "@/utils/private-attachments.js";
import {getIconByName} from "@/utils/icon-utils.js";
import {useSettingStore} from "@/store/setting.js";
import {allEmailDelete} from "@/request/all-email.js";
import {useUiStore} from "@/store/ui.js";
import {useI18n} from "vue-i18n";
import {EmailUnreadEnum} from "@/enums/email-enum.js";

const uiStore = useUiStore();
const settingStore = useSettingStore();
const accountStore = useAccountStore();
const emailStore = useEmailStore();
const router = useRouter()
const email = emailStore.contentData.email
const showPreview = ref(false)
const srcList = reactive([])
const renderedHtml = ref('')
const remoteImages = ref(false)
const attachmentError = ref('')
const attachmentBusy = ref('')
const attachmentUrls = new Map()
const downloads = new AbortController()
let unmounted = false

const { t } = useI18n()
watch(() => accountStore.currentAccountId, () => {
  handleBack()
})

onMounted(() => {
  hydrateMailImages();
  if (emailStore.contentData.showUnread && email.unread === EmailUnreadEnum.UNREAD) {
    email.unread = EmailUnreadEnum.READ;
    emailRead([email.emailId]);
  }
})

onUnmounted(() => {
  unmounted = true;
  downloads.abort();
  for (const url of attachmentUrls.values()) URL.revokeObjectURL(url);
  attachmentUrls.clear();
  emailStore.contentData.showUnread = false;
})

function openReply() {
  uiStore.writerRef.openReply(email)
}

function openForward() {
  uiStore.writerRef.openForward(email)
}

function toMessage(message) {
  return  message ? JSON.parse(message).message : '';
}

async function attachmentUrl(key) {
  if (attachmentUrls.has(key)) return attachmentUrls.get(key);
  const blob = await privateAttachmentBlob(key, downloads.signal);
  if (unmounted) throw new Error('Cancelled');
  const url = URL.createObjectURL(blob);
  attachmentUrls.set(key, url);
  return url;
}

async function hydrateMailImages() {
  // Parse in an inert template so no tracker can load during preprocessing.
  const template = document.createElement('template');
  template.innerHTML = email.content || '';
  const domain = (settingStore.settings.r2Domain || '').replace(/\/$/, '');
  const domainUrl = domain.startsWith('https://') ? domain : (domain ? 'https://' + domain : '');
  renderedHtml.value = template.innerHTML;
  for (const img of template.content.querySelectorAll('img')) {
    const source = img.getAttribute('src') || '';
    let key = source.startsWith('{{domain}}') ? source.slice(10) : source;
    if (domainUrl && source.startsWith(domainUrl + '/attachments/')) key = source.slice(domainUrl.length + 1);
    if (!key.startsWith('attachments/')) continue;
    img.removeAttribute('src');
    try { img.setAttribute('src', await attachmentUrl(key)); }
    catch (error) { if (!unmounted) attachmentError.value = error.message; }
  }
  if (!unmounted) renderedHtml.value = template.innerHTML;
}

async function showImage(att) {
  if (!isImage(att.filename)) return;
  attachmentError.value = '';
  attachmentBusy.value = att.key;
  try {
    const url = await attachmentUrl(att.key);
    srcList.length = 0; srcList.push(url); showPreview.value = true;
  } catch (error) { if (!unmounted) attachmentError.value = error.message; }
  finally { attachmentBusy.value = ''; }
}

async function downloadAttachment(att) {
  attachmentError.value = '';
  attachmentBusy.value = att.key;
  try {
    const link = document.createElement('a');
    link.href = await attachmentUrl(att.key); link.download = safeFilename(att.filename);
    document.body.appendChild(link); link.click(); link.remove();
  } catch (error) { if (!unmounted) attachmentError.value = error.message; }
  finally { attachmentBusy.value = ''; }
}

function isImage(filename) {
  return ['png', 'jpg', 'jpeg', 'bmp', 'gif','jfif'].includes(getExtName(filename))
}

function formateReceive(recipient) {
  recipient = JSON.parse(recipient)
  return recipient.map(item => item.address).join(', ')
}

function changeStar() {
  if (email.isStar) {
    email.isStar = 0;
    starCancel(email.emailId).then(() => {
      email.isStar = 0;
      emailStore.cancelStarEmailId = email.emailId
      setTimeout(() => emailStore.cancelStarEmailId = 0)
      emailStore.starScroll?.deleteEmail([email.emailId])
    }).catch((e) => {
      console.error(e)
      email.isStar = 1;
    })
  } else {
    email.isStar = 1;
    starAdd(email.emailId).then(() => {
      email.isStar = 1;
      emailStore.addStarEmailId = email.emailId
      setTimeout(() => emailStore.addStarEmailId = 0)
      emailStore.starScroll?.addItem(email)
    }).catch((e) => {
      console.error(e)
      email.isStar = 0;
    })
  }
}

const handleBack = () => {
  router.back()
}

const handleDelete = () => {
  ElMessageBox.confirm(t('delEmailConfirm'), {
    confirmButtonText: t('confirm'),
    cancelButtonText: t('cancel'),
    type: 'warning'
  }).then(() => {
    if (emailStore.contentData.delType === 'logic') {
      emailDelete(email.emailId).then(() => {
        ElMessage({
          message: t('delSuccessMsg'),
          type: 'success',
          plain: true,
        })
        emailStore.deleteIds = [email.emailId]
      })
    } else  {

      allEmailDelete(email.emailId).then(() => {
        ElMessage({
          message: t('delSuccessMsg'),
          type: 'success',
          plain: true,
        })
        emailStore.deleteIds = [email.emailId]
      })
    }

    router.back()
  })
}
</script>
<style scoped lang="scss">
.attachment-download { background: none; border: 0; color: inherit; padding: 4px; display: flex; cursor: pointer; }
.attachment-download:focus-visible { outline: 2px solid currentColor; }
.box {
  height: 100%;
  overflow: hidden;
  display: grid;
  grid-template-rows: auto 1fr auto;
}

.header-actions {
  padding: 8px 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  box-shadow: var(--header-actions-border);

  .back-btn {
    width: 40px;
    height: 40px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: var(--md-sys-shape-corner-full);
    cursor: pointer;
    color: var(--md-sys-color-on-surface);
    transition: background var(--md-sys-motion-duration-short3) var(--md-sys-motion-easing-standard);
    flex-shrink: 0;

    &:hover {
      background: color-mix(in srgb, var(--md-sys-color-on-surface) 8%, transparent);
    }
  }

  .action-pills {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }

  .pill {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    height: 36px;
    padding: 0 16px 0 12px;
    border-radius: var(--md-sys-shape-corner-full);
    cursor: pointer;
    font: var(--md-sys-typescale-label-large);
    white-space: nowrap;
    transition: all var(--md-sys-motion-duration-short4) var(--md-sys-motion-easing-standard);
    user-select: none;
  }

  .pill-tonal {
    background: var(--md-sys-color-secondary-container);
    color: var(--md-sys-color-on-secondary-container);

    &:hover {
      box-shadow: var(--md-sys-elevation-1);
      background: color-mix(in srgb, var(--md-sys-color-on-secondary-container) 8%, var(--md-sys-color-secondary-container));
    }

    &:active {
      box-shadow: none;
    }
  }

  .pill-outlined {
    background: transparent;
    color: var(--md-sys-color-on-surface-variant);
    border: 1px solid var(--md-sys-color-outline);

    &:hover {
      background: color-mix(in srgb, var(--md-sys-color-on-surface-variant) 8%, transparent);
    }
  }

  .pill-filled {
    background: var(--md-sys-color-primary);
    color: var(--md-sys-color-on-primary);

    &:hover {
      box-shadow: var(--md-sys-elevation-1);
      opacity: 0.92;
    }

    &:active {
      box-shadow: none;
    }
  }
}


.bottom-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 12px 16px;
  background: var(--md-sys-color-surface-container);
  border-top: 1px solid var(--md-sys-color-outline-variant);

  .pill {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    height: 40px;
    padding: 0 24px 0 16px;
    border-radius: var(--md-sys-shape-corner-full);
    cursor: pointer;
    font: var(--md-sys-typescale-label-large);
    white-space: nowrap;
    transition: all var(--md-sys-motion-duration-short4) var(--md-sys-motion-easing-standard);
    user-select: none;
    flex: 1;
    max-width: 240px;
    justify-content: center;
  }

  .pill-tonal {
    background: var(--md-sys-color-secondary-container);
    color: var(--md-sys-color-on-secondary-container);

    &:hover {
      box-shadow: var(--md-sys-elevation-1);
      background: color-mix(in srgb, var(--md-sys-color-on-secondary-container) 8%, var(--md-sys-color-secondary-container));
    }

    &:active {
      box-shadow: none;
    }
  }
}

.scrollbar {
  height: 100%;
  width: 100%;
}

.container {
  font-size: 14px;
  padding-left: 20px;
  padding-right: 20px;
  padding-top: 10px;
  @media (max-width: 1023px) {
    padding-left: 15px;
    padding-right: 15px;
  }

  .email-title {
    font: var(--md-sys-typescale-headline-small);
    font-weight: 600;
    margin-bottom: 16px;
    color: var(--md-sys-color-on-surface);
  }

  .htm-scrollbar {
  }

  .content {
    display: flex;
    flex-direction: column;

    .att {
      margin-top: 24px;
      margin-bottom: 24px;
      border: 1px solid var(--md-sys-color-outline-variant);
      padding: 16px;
      border-radius: var(--md-sys-shape-corner-medium);
      width: fit-content;
      .att-box {
        min-width: min(410px,calc(100vw - 60px));
        max-width: 600px;
        display: grid;
        gap: 12px;
        grid-template-rows: 1fr;
      }

      .att-title {
        margin-bottom: 8px;
        display: flex;
        justify-content: space-between;
        span:first-child {
          font-weight: bold;
        }
      }

      .att-item {
        cursor: pointer;
        div {
          align-self: center;
        }
        background: var(--light-ill);
        padding: 5px 7px;
        border-radius: 4px;
        align-self: start;
        display: grid;
        grid-template-columns: auto 1fr auto auto;
        .att-icon {
          display: grid;
        }

        .att-size {
          color: var(--secondary-text-color);
        }

        .att-name {
          margin-left: 8px;
          margin-right: 8px;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
          word-break: break-all;
        }

        .att-image {
          width: 60px;
          height: 60px;
          object-fit: contain;
        }

        .opt-icon {
          padding-left: 10px;
          color: var(--secondary-text-color);
          align-items: center;
          display: flex;
          gap: 8px;
          cursor: pointer;
          a {
            color: var(--secondary-text-color);
            align-items: center;
            display: flex;
          }
        }
      }
    }

    .email-info {

      border-bottom: 1px solid var(--light-border-color);
      margin-bottom: 20px;
      padding-bottom: 8px;
      @media (max-width: 1024px) {
        margin-bottom: 15px;
      }
      .date {
        color: var(--regular-text-color);
        margin-bottom: 6px;
      }

      .email-msg {
        max-width: 400px;
        width: fit-content;
        margin-bottom: 15px;
      }

      .send {
        display: flex;
        margin-bottom: 6px;

        .send-name {
          color: var(--regular-text-color);
          display: flex;
          flex-wrap: wrap;
        }

        .send-name-title {
          padding-right: 5px;
        }
      }

      .receive {
        margin-bottom: 6px;
        display: flex;
        .receive-email {
          max-width: 700px;
          word-break: break-word;
        }
        span:nth-child(2) {
          color: var(--regular-text-color);
        }
      }

      .send-source {
        white-space: nowrap;
        font-weight: bold;
        padding-right: 10px;
      }

      .source {
        white-space: nowrap;
        font-weight: bold;
        padding-right: 10px;
      }
    }
  }
}

.shadow-html::after  {
  content: "";
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: var(--message-block-color); /* 半透明黑色蒙层 */
  pointer-events: none; /* 不影响点击 */
}

.email-text {
  font-family: inherit;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
}

.bottom-distance {
  margin-bottom: 30px;
}


</style>
