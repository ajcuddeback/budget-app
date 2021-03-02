let income = 0;
let bills = 0;
let leftOver = 0;

function calculateLeftOver() {
    let incomeAmount = document.querySelector('.total-income').innerHTML.split(": $")[1]
    let billAmount = document.querySelector('.total-expense').innerHTML.split(": $")[1]
    console.log(incomeAmount, billAmount)
    if (incomeAmount === undefined) {
        income = 0;
    } else {
        income = income + parseInt(incomeAmount);
    }

    if (billAmount === undefined) {
        bills = 0;
    } else {
        bills = bills + parseInt(billAmount);
    }

    leftOver = income - bills;

    const leftOverDiv = document.querySelector('.left-over');
    leftOverDiv.innerHTML = '';
    leftOverDiv.innerHTML = `Left Over: $${leftOver}`
    if (leftOver < 0) {
        leftOverDiv.style.border = '2px solid rgb(181, 34, 24)';
    } else if (leftOver <= 50 && leftOver >= 0) {
        leftOverDiv.style.border = '2px solid rgb(190, 199, 14)'
    } else {
        leftOverDiv.style.border = '2px solid rgb(20, 179, 33)';
    }

};

setTimeout(calculateLeftOver, 300)