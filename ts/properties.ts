/*******************************************************************************
 * Copyright (c) 2023 Maxprograms.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-v10.html
 *
 * Contributors:
 *     Maxprograms - initial API and implementation
 *******************************************************************************/

class Properties {

    electron = require('electron');

    currentId: string;
    currentType: string;
    props: Array<string[]>;

    constructor() {
        this.electron.ipcRenderer.send('get-theme');
        this.electron.ipcRenderer.on('set-theme', (event: Electron.IpcRendererEvent, arg: any) => {
            (document.getElementById('theme') as HTMLLinkElement).href = arg;
        });
        this.electron.ipcRenderer.send('get-unit-properties');
        this.electron.ipcRenderer.on('set-unit-properties', (event: Electron.IpcRendererEvent, arg: any) => {
            this.setUnitProperties(arg);
        });
        this.electron.ipcRenderer.on('set-new-property', (event: Electron.IpcRendererEvent, arg: any) => {
            this.setNewProperty(arg);
        });
        document.getElementById('addProperty').addEventListener('click', () => {
            this.addProperty();
        });
        document.getElementById('deleteProperties').addEventListener('click', () => {
            this.deleteProperties();
        });
        document.getElementById('save').addEventListener('click', () => {
            this.saveProperties();
        });

        document.addEventListener('keydown', (event: KeyboardEvent) => {
            if (event.code === 'Escape') {
                this.electron.ipcRenderer.send('close-properties');
            }
        });
        this.electron.ipcRenderer.send('properties-height', { width: document.body.clientWidth, height: document.body.clientHeight });
    }

    setUnitProperties(arg: any): void {
        this.currentId = arg.id;
        this.currentType = arg.type;
        this.props = arg.props;
        this.drawProperties();
    }

    saveProperties(): void {
        var lang = this.currentType === 'TU' ? '' : this.currentType;
        var arg = {
            id: this.currentId,
            lang: lang,
            properties: this.props
        }
        this.electron.ipcRenderer.send('save-properties', arg);
    }

    addProperty(): void {
        this.electron.ipcRenderer.send('show-add-property');
    }

    setNewProperty(arg: any): void {
        var prop: string[] = [arg.type, arg.value];
        this.props.push(prop);
        this.drawProperties();
        (document.getElementById('save') as HTMLButtonElement).focus();
    }

    deleteProperties(): void {
        var collection: HTMLCollection = document.getElementsByClassName('rowCheck');
        for (let i = 0; i < collection.length; i++) {
            var check = collection[i] as HTMLInputElement;
            if (check.checked) {
                this.removeProperty(check.parentElement.parentElement.id);
            }
        }
        this.drawProperties();
        (document.getElementById('save') as HTMLButtonElement).focus();
    }

    drawProperties(): void {
        var rows: string = '';
        for (let i = 0; i < this.props.length; i++) {
            var pair: string[] = this.props[i];
            rows = rows + '<tr id="' + pair[0] + '"><td><input type="checkbox" class="rowCheck"></td><td class="noWrap">' + pair[0] + '</td><td class="noWrap">' + pair[1] + '</td></tr>';
        }
        document.getElementById('propsTable').innerHTML = rows;
    }

    removeProperty(type: string): void {
        var copy: Array<string[]> = [];
        for (let i = 0; i < this.props.length; i++) {
            var pair: string[] = this.props[i];
            if (pair[0] !== type) {
                copy.push(pair);
            }
        }
        this.props = copy;
    }
}
