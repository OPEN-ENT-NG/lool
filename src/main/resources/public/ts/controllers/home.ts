import {ng, idiom, workspace, notify} from 'entcore';
import {extensions, initPostMessage} from "../sniplets/create";
import {Eventer} from "entcore-toolkit";
import {Element, Tree} from "entcore/types/src/ts/workspace/model";

interface ViewModel {
    newDocument : {
        extension: string,
        name: string,
        folder: any
    },
    workspaceTrees: Tree[],
    listFolders: any,
    openedFolder: Element,
    selectedFolder: Element,
    documentTypeList: any[],
    eventer: Eventer,
    display: {
        warning: boolean,
        lightbox: {}
    }

    $onInit(): Promise<void>;
    $onDestroy(): Promise<void>;
    initDocument(): void;
    createDocument(Event, string): void;
}


export const homeController = ng.controller('HomeController', ['$scope',
    function ($scope) {
        const vm: ViewModel = this;
        vm.newDocument = {
            extension: '',
            name: '',
            folder: null
        };
        vm.workspaceTrees = [];
        vm.listFolders = {};
        vm.openedFolder = null;
        vm.selectedFolder = null;
        vm.documentTypeList = [];
        vm.eventer = new Eventer();
        vm.display = {
            warning: false,
            lightbox: false
        }

        vm.$onInit = async () : Promise<void> => {
            let trees = null;
            try {
                trees = await workspace.v2.service.fetchTrees({ filter: "all", hierarchical: true });
                vm.workspaceTrees[0] = trees.filter(tree => tree.filter == "owner")[0];
                vm.workspaceTrees[0].name = idiom.translate("workspace.myDocuments");
            } catch (err) {
                notify.error(idiom.translate('lool.create.failed.loading'));
                throw err;
            }

            vm.listFolders = {
                cssTree: "folders-tree",
                get trees() {
                    return vm.workspaceTrees;
                },
                isDisabled(folder) {
                    return false;
                },
                isOpenedFolder(folder) {
                    if (vm.openedFolder === folder) {
                        return true;
                    }
                    else if ((folder as Tree).filter) {
                        if (!workspace.v2.service.isLazyMode()){
                            return true;
                        }
                    }
                    return vm.openedFolder && workspace.v2.service.findFolderInTreeByRefOrId(folder, vm.openedFolder);
                },
                isSelectedFolder(folder) {
                    return vm.selectedFolder === folder;
                },
                openFolder(folder) {
                    vm.newDocument.folder = folder._id;
                    vm.openedFolder = vm.selectedFolder = folder;
                }
            }

            idiom.addBundle('/lool/i18n', () => {
                if (vm.documentTypeList.length === 0) {
                    for (let ext of extensions) {
                        vm.documentTypeList.push({
                            extension: ext,
                            title: idiom.translate(`lool.sniplet.create-document.type.${ext}`)
                        })
                    }
                }
                vm.initDocument();
                initPostMessage((event) => {
                    const data = JSON.parse(event.data);
                    if (data.id === 'lool@getFolderResponse') {
                        vm.eventer.trigger(data.id, data);
                        if ('folderId' in data) {
                            vm.newDocument.folder = data.folderId;
                        }
                        $scope.$apply();
                        console.info(vm.newDocument);
                    }
                });
            });

            $scope.$apply();
        };

        // Functions

        vm.initDocument = () : void => {
            vm.newDocument = {
                extension: vm.documentTypeList[0].extension,
                name: '',
                folder: null
            };
            vm.selectedFolder = vm.listFolders.trees[0];
            vm.display.warning = false;
            $scope.$apply();
        };

        vm.createDocument = (e, type) : void => {
            if (vm.newDocument.name === undefined || vm.newDocument.name.trim() === '') {
                vm.display.warning = true;
                return;
            }
            vm.newDocument.extension = type.extension;
            e.preventDefault();
            console.info(vm.newDocument);
            window.open(`/lool/document?type=${vm.newDocument.extension}&name=${vm.newDocument.name}${vm.newDocument.folder ? `&folder=${vm.newDocument.folder}` : ''}`);
            vm.initDocument();
        };
    }
]);