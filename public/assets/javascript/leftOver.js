let income = 0;
let bills = 0;
let leftOver = 0;

function calculateLeftOver() {
    let incomeAmount = document.querySelector('.total-income').innerHTML.split(": ")[1]
    let billAmount = document.querySelector('.total-expense').innerHTML.split(": ")[1]
    if (incomeAmount === '<span></span>') {
        income = 0;
    } else {
        income = income + parseInt(incomeAmount);
    }

    if (billAmount === '<span></span>') {
        bills = 0;
    } else {
        bills = bills + parseInt(billAmount);
    }

    leftOver = income - bills;

    const leftOverDiv = document.querySelector('.left-over');
    leftOverDiv.innerHTML = '';
    leftOverDiv.innerHTML = `Left Over: ${leftOver}`

};

setTimeout(calculateLeftOver, 300)