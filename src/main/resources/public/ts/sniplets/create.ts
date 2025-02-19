import {Eventer} from 'entcore-toolkit';
import {Behaviours, idiom as lang} from 'entcore';

export const initPostMessage = (callback) => {
    const eventMethod = window.addEventListener ? 'addEventListener' : 'attachEvent';
    const eventer = window[eventMethod];
    const messageEvent = eventMethod == 'attachEvent' ? 'onmessage' : 'message';

    return eventer(messageEvent, callback, false);
};
export const capabilities = {};

declare let window: any;

let loolDocument = {
    extension: '',
    name: '',
    folder: null
};

enum ButtonType { PRIMARY = 'primary', SECONDARY = 'secondary' }

export const create = {
    title: 'lool.sniplet.create-document.title',
    description: 'lool.sniplet.create-document.description',
    controller: {
        eventer: new Eventer(),
        lightbox: {
            display: false
        },
        document: loolDocument,
        lang,
        documentTypeList: ([] as any[]),
        buttonType: ButtonType.SECONDARY,
        ButtonType,
        init: function () {
            console.debug('Init create sniplet');
            const that: any = this;
            console.log(this);
            this.buttonType = this.source.type;
            lang.addBundle('/lool/i18n', () => {
                if (that.documentTypeList.length === 0) {
                    that.initDocumentList();
                }

                this.initDocument();
            });

            initPostMessage((event) => {
                const data = JSON.parse(event.data);
                if (data.id === 'lool@getFolderResponse') {
                    this.eventer.trigger(data.id, data);
                    if ('folderId' in data) {
                        loolDocument.folder = data.folderId;
                    }
                    this.lightbox.display = true;
                    this.$apply();
                }
            });
        },
        initDocumentList: function () {
            console.debug('Init document template list');
            Behaviours.applicationsBehaviours['lool'].provider.templates.forEach((ext) => this.documentTypeList.push({
                extension: ext,
                title: lang.translate(`lool.sniplet.create-document.type.${ext}`)
            }));
        },
        initDocument: function () {
            console.debug('Init document');
            if (this.documentTypeList.length === 0) {
                this.initDocumentList();
            }

            loolDocument = {
                extension: this.documentTypeList[0].extension,
                name: '',
                folder: null
            };
            this.document = loolDocument;
        },
        openLightbox: function () {
            this.initDocument();
            const event = {
                id: 'lool@getFolder'
            };
            this.eventer.once('lool@getFolderResponse', (data) => loolDocument.folder = data.folderId);
            window.postMessage(JSON.stringify(event), window.location.origin);
        },
        createDocument: function (e: Event) {
            e.preventDefault();
            const forbiddenRegex = /[\/\\<>|]/g;
            this.document.name = this.document.name.trim().replace(forbiddenRegex, '');
            window.open(`/lool/document?type=${this.document.extension}&name=${this.document.name}${loolDocument.folder ? `&folder=${loolDocument.folder}` : ''}`);
            this.lightbox.display = false;
            this.initDocument();
        },
        preventForbiddenChars: function ($event) {
            var char = $event.key;
          
            var forbiddenRegex = /[\/\\<>|]/;
          
            if (forbiddenRegex.test(char)) {
              $event.preventDefault();
            }
        }
    }
}